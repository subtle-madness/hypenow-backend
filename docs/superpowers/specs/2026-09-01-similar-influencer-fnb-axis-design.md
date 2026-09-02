# 유사 인플루언서 추천 F&B 개방 — 카테고리 스탯·피어 뷰의 축 인지화

> 상태: 🟢 활성 · 작성 2026-09-01

- **트랙**: LL2(F&B 콘텐츠 분류)의 후속 — "F&B 서빙 개방"(08-31)·"발굴 카드 categoryShares 축 인지"(09-01 핫픽스 #681)에 이어지는 리포트 표면 마지막 조각
- **선행**: `beauty_taxonomy.axis`(V20260831032349) · `accounts.beauty/fnb`(08-31 서빙 개방) · `findShares(handles, fnbAxis)`(#681)
- **범위 밖**: 리포트 카테고리 믹스(`findCategories` — 이미 축 무관으로 동작), 피어 퍼센타일의 화면 서빙(죽은 컬럼 — FE 명세 도착 시 별도), 계정 카피 프롬프트의 뷰티 전제 중립화(LL2 후속 그대로), 홈/리빙 축

## 1. 문제 — F&B 리포트의 유사 추천이 잡탕이다

F&B 인플루언서 리포트(6.22)의 "유사 인플루언서" 탭이 **무관한 계정들을 추천한다**
(09-01 운영 실측: 먹방 계정 muk_gyumato의 유사 목록에 40대 엄마 라이프스타일·여행/뷰티
계정). 유사 카드의 카테고리 비중도 "카테고리 정보 준비 중"으로 비어 있다.

원인은 유사 추천(6.23, `findSimilarHandles`)의 세 기둥 전부에 박힌 뷰티 전제:

1. **후보 풀 구획** — 풀 기준인 `peer_category`(주 카테고리×팔로워 버킷)의 원천
   `account_category_stats`(V35)가 `is_beauty IS TRUE` 게이트라, F&B 계정은 분류 분포가
   0행 → **'미분류' 풀**로 떨어진다. '미분류' 풀 = 분류 게시물이 없는 잡탕 계정들.
2. **유사도 점수** — 점수의 40%인 카테고리 믹스 겹침이 같은 뷰를 읽어 F&B 계정은 믹스가
   빈 값 → traits Jaccard(60%)만 남는다.
3. **후보 게이트** — 뷰티 게시물 비율 20% 게이트가 후보에 걸려, F&B 계정은 비율 0%라
   전부 탈락(분석 8건 미만 계정만 보류로 통과 — 그래서 목록이 비는 대신 저분석 잡탕이 남는다).

부수 피해: 같은 뷰를 읽는 **계정 카피 LLM**(AccountAnalysisJob·ClaudeBurstRunner)도 F&B
계정 카피를 카테고리 컨텍스트 없이 생성 중이다.

한편 `account_peer_stats`(V39)의 퍼센타일 컬럼(top_pct_* 8종·중앙값 ER 2종)은 **현재 소비자가
없다**(운영 코드 전수 grep 0건 — was가 읽는 건 `handle, peer_category`뿐). 이 작업으로 축별로
준비되지만 화면 서빙은 범위 밖이다.

## 2. 설계 원칙 — 축별 분리, 기존 화면 불변 증명

발굴 필터(`account_category_share` 재정의)·발굴 카드(#681 `findShares`)와 동일 패턴:
게이트를 지우는 게 아니라 **데이터를 축으로 분리하고 요청 축의 것만 쓴다**. 축 소속은
어휘(`beauty_taxonomy.axis`)가 정본(LL2 §2).

혼합 계정(뷰티+F&B, 운영 1,211개)은 **두 축 모두에** 분포·피어를 갖는다. 뷰티 축 행은
기존 뷰가 내던 것과 동일 집합 — 기존 뷰티 화면(유사 목록 포함)은 불변이며, 운영 DB 전량
EXCEPT 대조로 증명한다(사용자 확정: 옵션 1 — "게이트만 제거(축 혼합)" 불채택 사유는
혼합 계정의 주 카테고리 뒤집힘이 기존 뷰티 피어 그룹·유사 목록을 흔드는 것).

## 3. analytics — 마이그레이션 1개 (analysis DB, UTC 채번)

### 3-1. `account_category_stats` 재정의 — axis 컬럼 추가 (같은 이름 유지)

```sql
SELECT s.account_handle,
       COALESCE(t.main_label, a.main_category) AS main_group,
       count(*)                                AS content_count,
       COALESCE(t.axis, CASE WHEN a.is_beauty THEN 'beauty' ELSE 'fnb' END) AS axis
FROM account_content_series s
JOIN content_analyses a ON a.short_code = s.short_code
LEFT JOIN (SELECT DISTINCT main_value, main_label, axis FROM beauty_taxonomy) t
       ON t.main_value = a.main_category
WHERE a.main_category IS NOT NULL
GROUP BY s.account_handle, 2, 4
```

- 게이트 `is_beauty IS TRUE` 제거 + `axis` 컬럼(맨 끝). 어휘 밖 main_category는 라벨 폴백
  규약(V35)을 유지하며 axis도 `is_beauty` 폴백 — 운영 실측 어휘 밖 0건이라 이론 방어.
- **같은 이름 유지가 안전한 근거**(구 코드 롤링 공존):
  - 계정 카피 잡 2곳: `SELECT main_group, content_count WHERE account_handle=?` —
    행 추가는 F&B 컨텍스트 유입(개선)이고, main_group은 축을 가로질러 중복되지 않는다
    (대분류가 축을 결정). 카피는 stale 주기 재생성이라 즉시 영향도 없다.
  - 구 was `findSimilarHandles`의 믹스 CTE: 혼합 계정의 믹스 분모가 롤링 창(수 분) 동안
    양축 합산으로 계산될 수 있다 — 점수 미세 변동이지 오류 아님. 수용.
- 불변식: `axis='beauty'` 투영 ≡ 구 뷰 (LL2 불변식 `main NOT NULL ∧ axis=beauty ⟺
  is_beauty=true ∧ main NOT NULL` + 폴백).

### 3-2. `account_peer_axis_stats` 신설 + 구 이름은 뷰티 투영으로

`(handle, axis)` 단위 피어 뷰를 **새 이름으로** 만들고, 구 `account_peer_stats`는
`WHERE axis='beauty'` 투영으로 재정의한다(allow-destructive: DROP 직후 같은 트랜잭션
재생성, 소비자는 같은 릴리스의 was).

- 새 이름이 필요한 이유: 행이 계정당 1→2가 되므로 같은 이름을 유지하면 롤링 중 구
  `findSimilarHandles`의 peers CTE에 핸들이 중복돼 유사 목록이 깨진다. 투영 유지가
  expand — 구 이름 뷰 제거는 다음 릴리스의 contract 판단.
- 정의: V39 body에서 `base`를 `account_summaries CROSS JOIN (VALUES ('beauty'),('fnb')) ax`
  로 확장, `cat`은 3-1 뷰에서 `(handle, axis)`별 지배 main_group. 모든 파티션
  (`peer_category, follower_bucket` → + `axis`)과 `med`에 axis 추가.
- `gmed`(전역 중앙값 ER)는 축별 파티션으로 바꿔도 **값이 동일하다** — base가 전 계정을
  양축에 LEFT JOIN으로 싣기 때문에 축별 모수 = 전 계정으로 같다. 뷰티 투영 동치가
  성립하는 근거.
- 분류 게시물이 없는 축의 peer_category는 기존 규약대로 '미분류'(양축 모두).
- 비용: 뷰 계산량 ×2(일반 VIEW, 유사 쿼리에서 MATERIALIZED CTE로 1회 평가 — 현 중앙값
  753ms 기준 수백 ms 증가 가능). 초기 수용, 느리면 matview화(DerivedViewRefresher 패턴
  기성)가 후속.

## 4. was — 유사 추천 경로의 축 파라미터

- **대상 계정 축 파생** (`V2InfluencerReportRepository.findAxis(handle)` 신설):
  `COALESCE(a.fnb,false) AND NOT COALESCE(a.beauty,true)` — **F&B 단독 → fnb, 뷰티·혼합·
  레거시(null) → beauty**(기존 화면 불변 우선, 발굴 무필터의 COALESCE 방향과 동일 논리).
  컨트롤러가 1회 조회해 아래 두 곳에 전달.
- **`findSimilarHandles(handle, fnbAxis)`**:
  - peers CTE → `account_peer_axis_stats WHERE axis = :axis`,
    cats CTE → `account_category_stats WHERE axis = :axis`.
  - 후보 게이트 분기(발굴과 동일 패턴): 뷰티 축 = 기존 뷰티 비율 게이트 유지(결과 불변),
    F&B 축 = `COALESCE(ac.fnb, false)` + 뷰티 비율 게이트 미적용(걸면 전멸 — 발굴 §3과
    같은 근거). 휴면 제외·카피 보유·컷 0.30·상위 10은 양축 공통.
- **유사 카드 `findShares`**: 하드코딩 `false` → 파생 축(#681이 남긴 TODO 코멘트 해소).
  F&B 축 후보는 풀 구성상 분류 게시물 보유 계정이라 카드 비중이 채워져 나온다.
- 계정 카피 잡·`findCategories`(리포트 믹스)는 **무접촉**.

## 5. 기본 화면 불변 증명 지점 (테스트가 지켜야 할 것)

1. `account_category_stats`: 신 뷰 `WHERE axis='beauty'` 투영(구 3컬럼) ≡ 구 정의 —
   운영 전량 EXCEPT 0건 + 테스트 시드 동치.
2. `account_peer_stats`(투영) ≡ 구 V39 — 운영 전량 EXCEPT 0건(퍼센타일·중앙값 포함).
3. 뷰티 대상 유사 목록: 축 파라미터 도입 후에도 결과 동일(순서 포함) — 테스트 시드로 검증.
4. F&B 대상 유사 목록: 후보가 F&B 계정으로만 구성, '미분류' 잡탕·뷰티 계정 미혼입.
5. 혼합 계정: 대상일 때 beauty 축(기존 유사 목록 유지), 후보일 때 양축 풀에 모두 등장.
6. F&B 유사 카드의 categoryShares가 fnb 축 비중으로 채워진다.

## 6. 배포·마이그레이션 규약

- 마이그레이션은 UTC 타임스탬프 채번, `-- allow-destructive`(구 피어 뷰 DROP+투영 재생성,
  같은 트랜잭션) 주석. DROP COLUMN 없음 — 백필 짝 검사 비해당.
- 배포 순서는 LL2와 동일: analytics(뷰 적용) 먼저 — develop→staging→main 승격 CD가
  같은 릴리스로 보장. **핫픽스 경로 불가**(마이그레이션 동반).
- 롤링 공존: §3-1(카피 잡 무해·믹스 미세 변동 수용)·§3-2(구 이름 투영으로 무접촉).

## 7. 검증 계획

- was: `V2InfluencerReportRepositoryTest` 픽스처의 뷰 DDL 사본을 신 정의로 갱신 +
  §5 시나리오. `V2InfluencerReportControllerTest` 목 시그니처 갱신.
- analytics: `AccountPeerStatsViewTest`에 축 시나리오·투영 동치 추가.
- 운영: 마이그레이션 적용 후 §5-1·2 EXCEPT 대조(읽기 전용), F&B 계정 실계정으로 유사
  API 스팟체크(muk_gyumato 등).

## 8. 수용한 트레이드오프

- 유사 쿼리 비용 증가(뷰 양축 계산) — 실측 후 필요시 matview화.
- 롤링 창 수 분간 구 코드의 믹스 점수 미세 변동(§3-1).
- F&B 축 유사도의 traits는 뷰티 코퍼스에서 학습된 카피 산출물 — 품질은 F&B 카피가
  쌓이며 개선(LL2 후속 "프롬프트 축 중립화"와 연동).
