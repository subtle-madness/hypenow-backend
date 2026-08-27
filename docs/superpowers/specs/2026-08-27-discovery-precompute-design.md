# 6.21 발굴 목록 사전집계 + count 통합 설계

> 상태: 🟢 활성 · 작성 2026-08-27

## 1. 배경 — 왜

`GET /v1/influencers`(6.21 발굴 목록)가 mainCategory 계열 필터에서 4~11초. 근본 원인은 운영
EXPLAIN ANALYZE 실측으로 확정했다(요청 `emj7j4s6`, 08-27 12:02 KST, 총 11.4s):

1. **mainCategory 비중 게이트가 상관 서브쿼리** — `account_summaries` 전 행(6,210계정)마다
   창 내 게시물×분석을 집계(SubPlan 6,210회, ~560ms + 버퍼 31만 블록). 필터로 모수를 먼저
   줄일 수단이 없다.
2. **`account_beauty_ratio`가 일반 뷰** — 요청마다 7.3만×15.4만 행 조인 풀 집계(~570ms).
3. **sp(협찬 수) 서브쿼리도 전 계정 풀 집계**(~175ms).
4. **위 전부를 `findCards`+`countCards`가 각각 실행** — 요청당 2회(11.4s 중 count가 3.1s).

증폭 요인: shared_buffers 128MB(기본값) 콜드 I/O ~480MB + 2 vCPU에서 필터 연타 동시 요청.
페이지는 Redis 1h 캐시라 느린 건 전부 캐시 미스 조합이다. 프론트 요청 목표 p90 2s.

핵심 관찰: 집계 입력(`account_content_series`, `content_analyses`)은 **밤 잡 체인에서만
변한다**(MIRROR 04:30 KST → ANALYZE 05:00 → BATCH_COLLECT ~11:40 → LATE_BACKFILL 06:00).
낮 동안 정적이므로 사전집계의 신선도 손실이 실질 0이다.

## 2. 결정 — A안: matview 사전집계 3종 + 쿼리 조인 전환 + count 통합

기각한 대안: (B) 쿼리 재작성만 — 요청당 풀 집계 ~1s+가 남아 콜드에서 p90 2s 미보장.
(C) refresh를 별도 크론 잡으로 — 갱신 시점이 입력 변화와 분리돼 불필요한 지연·잡 추가.

기존 패턴 준수: `account_beauty_ratio`(V45)·`account_category_stats`(V35)가 확립한
**"analysis DB 파생 뷰(미러 아님)"** 자리를 그대로 쓰되 materialized로 바꾼다. 소유는
analytics(`db/migration/analysis`), was는 읽기만 — 경계 규칙(ARCHITECTURE §4-4) 무변경.

## 3. 스키마 (analytics 마이그레이션, UTC 타임스탬프 채번)

한 파일에서:

```sql
-- allow-destructive: 같은 이름·컬럼의 materialized view로 즉시 재생성 (사전집계 전환)
DROP VIEW account_beauty_ratio;
CREATE MATERIALIZED VIEW account_beauty_ratio AS
  <V45와 동일 SELECT> WITH DATA;
CREATE UNIQUE INDEX ux_account_beauty_ratio ON account_beauty_ratio (account_handle);

-- 계정×대분류 비중 — 발굴 게이트(≥20)와 동일 분모·round 산식.
-- 게이트 원식: round(100.0 * count FILTER (main=:mc) / count(*)) — 모수는
-- is_beauty IS TRUE AND main_category IS NOT NULL 창 내 게시물.
CREATE MATERIALIZED VIEW account_category_share AS
SELECT s.account_handle, an.main_category,
       round(100.0 * count(*) / sum(count(*)) OVER (PARTITION BY s.account_handle))::int AS pct
FROM account_content_series s
JOIN content_analyses an ON an.short_code = s.short_code
WHERE an.is_beauty IS TRUE AND an.main_category IS NOT NULL
GROUP BY s.account_handle, an.main_category
WITH DATA;
CREATE UNIQUE INDEX ux_account_category_share ON account_category_share (account_handle, main_category);

CREATE MATERIALIZED VIEW account_sponsored_counts AS
SELECT s.account_handle, count(*) AS cnt
FROM account_content_series s
JOIN content_analyses an ON an.short_code = s.short_code AND an.ad_type = 'sponsored'
GROUP BY s.account_handle
WITH DATA;
CREATE UNIQUE INDEX ux_account_sponsored_counts ON account_sponsored_counts (account_handle);
```

동치 논거(게이트): 원식은 `:mc` 게시물 0건이면 0(≥20 false), matview는 그 (계정,카테고리)
행이 없어 EXISTS false — 결과 동일. unique index는 `REFRESH CONCURRENTLY` 필수 조건.
`WITH DATA`라 마이그레이션 적용 즉시 서빙 가능(첫 refresh 대기 없음).

## 4. refresh — analytics 잡 훅

새 컴포넌트 `DerivedViewRefresher`(analysis DataSource, `REFRESH MATERIALIZED VIEW
CONCURRENTLY` ×3, 소요 로그). `AnalyticsJobService.trigger()`에서 **입력을 쓰는 잡**
(MIRROR, ANALYZE, LATE_BACKFILL_ANALYZE, BATCH_COLLECT) 성공·부분성공 후 호출한다.
refresh 실패는 자체 try/catch로 log.error만 — 잡 결과를 오염시키지 않는다(다음 잡 훅이
재시도 기회). CLASSIFY(댓글)·ACCOUNT_ANALYZE·SYNTHESIS 계열은 입력 무관이라 제외.
행 수가 6.2천 규모라 refresh 1회 ~1초대, 나이트리 체인에 무시 가능한 비용.

## 5. was 쿼리 변경 (`V1InfluencerDiscoveryRepository`)

1. **sp 서브쿼리 → matview LEFT JOIN**: `LEFT JOIN account_sponsored_counts sp ON
   sp.account_handle = su.handle` — `sp.cnt` 참조는 그대로. 핸들 푸시다운 템플릿
   (`FROM_JOINS_TEMPLATE`/`FROM_JOINS_BY_HANDLES` 분기)은 존재 이유가 사라져 삭제,
   `findCardsByHandles`도 같은 FROM을 쓴다.
2. **mainCategory 게이트 → matview EXISTS**:
   `AND EXISTS (SELECT 1 FROM account_category_share cs WHERE cs.account_handle = su.handle
   AND cs.main_category = :mainCategory AND cs.pct >= 20)`.
3. **count 통합**: `findCards` SELECT에 `count(*) OVER () AS total_count` 추가(윈도우는
   LIMIT 적용 전 전체에 대해 계산된다). `CardRow`에 `Long totalCount` 추가,
   `findCardsByHandles`는 `NULL::bigint AS total_count`를 채운다.
   `V1InfluencerDiscoveryPageService`는 첫 행의 totalCount를 쓰고, **0행일 때만**
   (offset 초과·공집합) `countCards` 폴백. `countCards`는 폴백 전용으로 유지.
4. 뷰티 비율 게이트 SQL·mid/subCategory EXISTS·키워드(q) 경로는 무변경 — mid/sub는
   게이트 통과 후 소수 행에만 실행돼 저비용(emj7j4s6 실측 ~50ms), 이번 범위 제외.

`account_category_stats`(V35)·`findShares`(카드 보강 쿼리)는 손대지 않는다 — 표면이 다르고
비용도 이미 수십 ms.

## 6. 동작 변화 (명시)

게이트·협찬수·뷰티비율 반영 시점이 "조회 즉시" → "입력을 쓴 잡 완료 직후". 입력이 그
잡들에서만 변하므로 관찰 가능한 차이는 없다. 페이지 Redis 1h 캐시가 이미 그 이상의 지연을
허용하고 있다.

## 7. 검증

- **동치 대조(운영, 읽기 전용)**: matview 정의를 동일 SQL의 CTE로 인라인한 신 쿼리 vs 구
  쿼리를 전 mainCategory 값 × 기본 정렬로 실행, 핸들 목록·total 완전 일치 확인(07-30
  푸시다운 전수 대조 선례). EXPLAIN ANALYZE로 개선 폭 실측.
- **was 테스트**: `V1InfluencerDiscoveryRepositoryTest`의 인라인 DDL 사본에 신규 파생 객체
  2종을 뷰로 추가(기존 V45 사본 관용구 — 리포지토리 SQL은 뷰/matview를 구분하지 않는다).
  total 통합 일치·0행 폴백·게이트 동작 테스트 추가.
- **analytics 테스트**: Flyway 적용 검증(기존 FlywaySchemaTest 경로) + refresher IT(시드
  변경 → refresh → matview 반영).

## 8. 배포

같은 릴리스에 마이그레이션(analytics Flyway)+양쪽 코드. 신 was가 뜨기 전 analytics Flyway가
먼저 끝나는 게 통상 순서라 위험 창은 수 초(§V45+07-30 선례와 동일). 롤링 중 구 was는
`account_beauty_ratio`를 이름으로만 참조하므로 matview 전환에 영향 없다.

## 9. 비범위 / 후속

- `shared_buffers` 128MB 상향(1~2GB) — 별도 인프라 작업으로 분리.
- mid/subCategory·키워드 경로 사전집계 — 실사용·실측상 불필요(YAGNI).
- `/brand-monitoring/accounts/:id/posts` 등 다른 느린 API — 별건.
