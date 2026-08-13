# 발굴 표면 성능 개선 — account_discovery_stats 물화 뷰 + 브랜드 협업 단일 패스

> 상태: ✅ 구현됨 · 2026-08-13 설계 승인·같은 날 구현

## 배경 (pg_stat_statements 개통 후 실측)

08-13 pg_stat_statements 개통(README §15-1) 직후 실측에서 발굴 표면 두 곳이 최상위 병목으로 확인됐다.

1. **`GET /v1/influencers`(발굴 목록)** — p95 1.7s, 캐시 냉각 시 최대 2.2s.
   `V1InfluencerDiscoveryRepository`의 FROM 절이 요청마다
   - `sp`: `account_content_series(6.9만) × content_analyses(12.6만)` 협찬 수 전 계정 집계 (~153ms)
   - `br`: 같은 조인의 뷰티 비율 뷰 `account_beauty_ratio`(V45) 전 계정 집계 (~180ms)
   를 새로 계산하고, `countCards`가 **같은 조인을 한 번 더** 친다(건당 1.27s 실측). 페이지
   프리페치(PagePrefetcher)까지 겹치면 페이지 이동 1회 = 풀 집계 최대 8회. 비용이 계정 수가
   아니라 `content_analyses` 행 수에 비례해 시간이 갈수록 선형으로 악화된다.
2. **`GET /v2/influencers/{id}/ai-report`의 `findBrandCollabs`** — 평균 1.8s.
   `others_json` 상관 서브쿼리가 **브랜드 하나마다** 전체 협찬 게시물 × `detected_brands`
   jsonb 전개를 재스캔한다(브랜드 N개 = 풀스캔 N회).

`/v2/influencers/{id}/similar`(`findSimilarHandles`)도 같은 `account_beauty_ratio` 전 계정
집계를 조인하므로 동일 구조 문제를 공유한다.

## 결정

### ① 물화 뷰 `account_discovery_stats` (analysis DB, analytics 소유)

```sql
CREATE MATERIALIZED VIEW account_discovery_stats AS
SELECT s.account_handle,
       count(*) FILTER (WHERE an.is_beauty IS NOT NULL) AS analyzed_count,
       count(*) FILTER (WHERE an.is_beauty IS TRUE)     AS beauty_count,
       count(*) FILTER (WHERE an.ad_type = 'sponsored') AS sponsored_count
FROM account_content_series s
JOIN content_analyses an ON an.short_code = s.short_code
GROUP BY s.account_handle;
CREATE UNIQUE INDEX ... ON account_discovery_stats (account_handle);
```

- V45 `account_beauty_ratio` 정의에 **`sponsored_count`(ad_type 정본)** 컬럼만 추가한 집계를
  스냅샷으로 저장. 유니크 인덱스는 `REFRESH ... CONCURRENTLY`(서빙 무중단 갱신) 전제 조건.
- **갱신 주체는 analytics**(시스템 경계 — was는 분석 결과 읽기 전용): `content_analyses`를
  변경하는 잡 3곳(일상 `ContentAnalysisJob`·`GeminiBackfillRunner` collect·`ClaudeBurstRunner`)과
  창 멤버십을 바꾸는 **미러 완료 시점**에 REFRESH. 집계 자체가 ~0.4s라 배치 비용 무시 가능.
- was 세 경로(`findCards`/`countCards`, `findCardsByHandles`, V2 `findSimilarHandles`)의
  `br`+`sp` 조인을 이 물화 뷰 단일 조인으로 교체. `sp` 핸들 푸시다운 변형
  (`FROM_JOINS_BY_HANDLES`, 07-30 최적화)은 물화로 존재 이유가 사라져 **템플릿 분기를 제거**한다.
- **기각한 대안**: `account_summaries` 컬럼 확장 — 이 테이블은 미러가 raw
  `v_account_summaries`에서 통째로 덮어쓰는 미러 소유물이고, 기존 `sponsored_count` 컬럼은
  유료파트너십 태그(릴스 전용) 기준의 옛 정의라 정본(ad_type)과 다르다(07-17 AccountAdCanon
  경위 참조). 분석 층 산출물을 미러 테이블에 섞으면 소유권·덮어쓰기 문제가 생긴다.
- **카운트 쿼리 병합(window count) 기각**: 물화 후 count 쿼리는 수십 ms — CardRow·조립부
  변경 비용 대비 이득 없음.

### ② `findBrandCollabs` 단일 패스 재작성

브랜드별 상관 서브쿼리를 "mine 브랜드 목록에 한정한 (브랜드×계정) 집계 1회 +
`row_number() OVER (PARTITION BY name ORDER BY cnt DESC, last_at DESC)` 상위 5"로 교체.
출력(브랜드별 cnt·contentIds·otherInfluencers 상위 5, 정렬 규칙) 완전 동일 — 스캔만 N회→1회.
물화 뷰와 무관한 순수 쿼리 재작성.

## 트레이드오프 (사용자 승인됨)

뷰티 게이트·협찬 수가 **요청 시점 실시간 → 분석 배치 종료 시점 스냅샷**이 된다.
원본 자체가 배치로만 변하므로(미러 04:30 → 분석 05:00 → 백필 06:00, 각 종료 시 REFRESH)
실사용 지연은 미러~분석 사이 약 30분 창이 전부다. 발굴 목록 특성상 무해로 판단.

## 마이그레이션·전환 규율

- 신규 마이그레이션은 UTC 타임스탬프 채번(analytics `db/migration/analysis`).
- **V45 뷰는 이번 릴리스에 남긴다**(expand-contract) — was 참조가 모두 물화 뷰로 넘어간
  다음 릴리스에서 DROP.
- was 통합 테스트는 시드 후 `REFRESH MATERIALIZED VIEW account_discovery_stats`를 명시
  호출해야 한다(물화 뷰는 시드 시점 데이터를 자동 반영하지 않음).

## 성공 기준

- `/v1/influencers` p95: 1.7s → 100ms 미만 (pg_stat_statements·프로메테우스로 배포 후 확인).
- `findBrandCollabs` 평균: 1.8s → 300ms 미만.
- 기존 테스트 전부 통과 + 게이트·협찬 필터·brandCollabs 동등성 테스트 신규 통과.

## 동반 변경

- deploy/README.md §15-1: 개통 3단계 `GRANT USAGE ON SCHEMA public TO grafana_reader` 추가
  (08-13 개통 때 실측 — grafana_reader가 public 스키마 USAGE 없이는 뷰가 안 보인다).
