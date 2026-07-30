# 계정 하입 스코어 — 고정 분모 (표본 하한 없음 결함 해소, 트랙 Z 후속)

> 상태: 🟢 활성 · ✅ 구현됨(PR 대기)
> 날짜: 2026-07-31
> 선행 스펙: `2026-07-30-hype-score-v3-decay-after-mapping-design.md`(§9 계정 척도 재교정,
> §10 소수점 노출·출력 매핑) — 이 스펙은 §7 "계정 점수 표본 하한 없음"(알려진 미해결)의 해소.

## 1. 문제

test 스택 실측(발굴 목록 상위권 점검)에서 최근창 점수산출 콘텐츠가 1~2건뿐인 계정이 상위권에
섞이는 사례를 확인했다:

- `ynp.ny` — 점수산출 콘텐츠 2건, `avg_hype_score_precise` 기준 7위
- `sunyvvin` — 점수산출 콘텐츠 1건, 8위
- `zero_lyrical` — 점수산출 콘텐츠 1건, 12위

`docs/hype-score.md` §7에 이미 "계정 점수 표본 하한 없음" 결함으로 기록돼 있던 항목이다.

## 2. 원인 실측

`v_account_summaries`의 `avg_hype_precise_raw`(및 구 `avg_hype_raw`)는 최근창(기본 12개,
`analytics.recent-window`) 콘텐츠의 `hype_score_output(hype_score_raw(...))`를 **단순 평균**
(`avg()`, 분모 = 창에 실제로 든 행 수 = `analyzed_count`)한 값이다.

운영 DB(crawler) 실측:

- 창이 12개로 꽉 찬 계정 6,350개 중 **2,633개(41%)**가 점수산출 콘텐츠 <12건
- 그 결손의 **99.9%가 `likes`/`comments_count` NULL**(크롤러 수집 누락) — 릴스 조회수 결측 등
  다른 NULL 사유는 사실상 없음

`avg()`는 분모가 표본 수 자체라, 점수산출 콘텐츠 1건이 만점에 가까운 값이면 그 계정의
평균도 만점에 가깝게 나온다 — 12건을 채워 성실히 활동한 계정과 구분되지 않는다.

## 3. 사용자 결정 — 수집 누락이어도 감점한다

인플루언서 상세 화면은 최근창 콘텐츠를 **최대 12개 카드**로 보여준다. 점수산출이 안 되는
게시물(수집 누락으로 `likes`/`comments_count`가 없는 게시물)은 애초에 화면에 뜨지 않는다 —
그 게시물이 존재하는지 유저는 알 수 없다. 따라서 "점수산출 콘텐츠 1건뿐인 계정"은 유저가
보는 화면 기준으로 "게시물을 1개만 올린 계정"과 **구분되지 않는다**. 원인이 계정의 활동
부족이 아니라 크롤러 수집 누락이라는 사정은 유저 화면에 반영되지 않으므로, 화면·점수 정합성을
우선해 **수집 누락도 감점 사유로 받아들인다**(사용자 결정, 재논의 대상 아님).

## 4. 새 산식 — 평균 → 고정 분모 합

```
avg_hype_precise_raw = sum(창 콘텐츠 출력매핑 점수) / analytics.recent-window
```

- 분모를 `analyzed_count`(창에 실제로 든 콘텐츠 수)에서 `analytics.recent-window`(창 크기
  설정값, 기본 12)로 고정한다 — 리터럴이 아니라 `01_recent_window.sql`이 읽는 것과 동일한
  `app_setting` 키를 그대로 읽어(새 상수를 만들지 않음), 재배포 없이 같이 튜닝된다.
- `sum()`은 NULL을 무시한다 — 점수산출 불가 콘텐츠(위 99.9% 사유인 likes/comments NULL)는
  분자에 자연히 0 기여(=감점)가 되고, 분모는 여전히 창 크기로 고정되므로 "표본이 적을수록
  유리해지는" 구조가 없어진다.
- 창 전체가 점수산출 불가(전부 NULL)면 `sum()` 자체가 NULL이라 `NULL/분모=NULL` — "창 전체
  점수 불가 → 결과 NULL"이라는 기존 계약이 그대로 유지된다(별도 분기 불필요).
- 0점 콘텐츠(정상적으로 점수 0인 콘텐츠, NULL이 아님)는 `sum()`에 실제 0으로 더해진다 —
  고정 분모가 그 0을 다른 값으로 밀어올리지 않는다(0점 보존, 회귀 테스트로 확인).
- 구현은 `analytics/views/10_account_detail.sql`의 `v_account_summaries` base CTE
  `avg_hype_precise_raw` 컬럼 하나만 바뀐다. `avg_hype_score`(bigint)·`avg_hype_raw`(구
  정렬 키, 값·의미 불변 동결 컬럼)는 여전히 구 `avg()` 산식을 쓰며 이번 변경과 무관하다 —
  다음 릴리스 DROP 대상이라 재교정하지 않는다.

## 5. 앵커 재적합

분모가 바뀌면 `avg_hype_precise_raw`(따라서 `avg_hype_score_precise`의 입력 raw)의 분포
자체가 이동한다 — 점수산출 콘텐츠가 창을 못 채운 계정들이 고정 분모로 낮은 raw를 받으면서
raw 모집단의 분위수가 아래로 이동했다. `analytics.hype_account_score_precise()`의 앵커를
`analytics/check/hype-anchor-refit.sql`의 계정 소수점 앵커 섹션(고정 분모 반영판)으로
재적합했다:

| 분위 | 구값(단순 평균) | 신값(고정 분모) |
|---|---|---|
| p05 | 1.4856 | **1.2417** |
| p50 | 23.6566 | **19.4383** |
| p90 | 56.3961 | **52.2401** |
| p99 | 77.0479 | **74.0179** |

`app_setting` 키(`analytics.hype-anchor-acct-precise-{p05,p50,p90,p99}`)는 그대로 두고,
함수 내 `COALESCE` 기본값만 교체했다(기준값의 단일 소스는 함수 기본값 — 기존 관용구와 동일).

## 6. 범위 밖 / 건드리지 않은 것

- `hype_account_score`(구, bigint)·`avg_hype_score`·`avg_hype_raw` — 값·의미 불변 동결,
  다음 릴리스 DROP 대상이라 재교정하지 않는다(§4 참조).
- 콘텐츠 단건 점수 산식·앵커(`analytics.hype_score`, `hype_score_output`) — 무변경.
- `analytics.recent-window` 자체의 기본값(12) — 무변경, 기존 설정 키를 읽기만 한다.
- 마이그레이션 없음 — 새 컬럼·새 앵커 키 없이 기존 함수 COALESCE 기본값과 뷰 SQL만 교체.

## 7. 검증

- `analytics/test/10_account_score_rescale.test.sql`에 회귀 3종 추가:
  1. **핵심 회귀**: 개별 콘텐츠 점수가 완전히 동일한 두 계정(점수산출 1건 vs 12건)을 만들어
     1건짜리의 `avg_hype_score_precise`가 12건짜리보다 뚜렷하게(실측 비율 약 4.99배, 여유를 둬
     3배로 단언) 낮음을 확인. 구 코드(`avg()`)로는 두 계정의 raw가 완전히 같아(66.3988...)
     `avg_hype_score_precise`도 완전히 같았을 것(91.0525)을 테스트 추가 전 수동 SQL로 직접
     확인 — 회귀가 실재했음을 구현 전에 재현.
  2. 창 전체 점수 불가 → `avg_hype_score_precise` NULL 보존(기존 `dummy_rawnull` 픽스처 재사용).
  3. 0점 보존 — 전 게시물 점수 0인 계정(`likes=0·comments=0`, 창 12건 꽉 참)이
     `avg_hype_score_precise=0`을 유지(NULL도 아니고 다른 값으로 밀어올려지지도 않음).
- `analytics/test/run.sh` 전체 `ALL GREEN`.
- 기존 단조성·상한·NULL·앵커 오버라이드 반응·정렬 키 분리·정수 동점 제거 회귀는 전부 유지
  (앵커점 매핑 테스트만 새 앵커 리터럴로 갱신, 판정 로직은 무변경).
