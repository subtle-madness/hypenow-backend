# 하입 스코어 — 산식·상수 정본 지도

> **항상 최신 유지 문서.** 산식·상수·표면·배포 상태가 바뀌면 같은 PR에서 이 파일을 갱신한다.
> 설계 경위(왜 그렇게 결정했나)는 `docs/superpowers/specs/`의 dated 스펙에 있고 **내용 불변**이다 —
> 이 문서는 "지금 무엇이 어떻게 돌고 있나"만 담는다.

## 1. 산식

**정본은 SQL 함수 하나**다: [`analytics/views/02_serving.sql`](../analytics/views/02_serving.sql) `analytics.hype_score()`.
Java에는 계산 로직이 없다 — was·contract-analysis는 미러된 값을 그대로 통과시킨다.

```
reach(릴스)  = ln(1 + views / (followers + B))
engage(릴스) = ln(1 + ((likes + 3·comments) / (followers + B)) / e0)
engage(피드) = ln(1 + ((likes + 3·comments) / (followers + B)) / f0)

Q = wr·reach + we·engage   (릴스)
Q = engage                 (피드 — views를 쓰지 않는다)

base  = clamp( 타입별 4점 앵커 구간선형(Q), 0, 100 )
raw   = base × 0.5^(max(경과일, 0) / halflife)        -- 반올림 전 연속값 (analytics.hype_score_raw)
score = round( raw )                                  -- 정수 — contents.hype_score (값·의미 불변, 07-30~)
표시  = round( 출력매핑(raw), 4 )                      -- 소수 — contents.hype_score_precise (07-30~, §1-1)
```

**감쇠는 앵커 매핑 뒤에 곱하고, 클램프는 감쇠 앞에 둔다**(v3~). 둘 다 이유가 있다:

- 감쇠를 `Q`에 먼저 곱하면 앵커 캘리브레이션이 코퍼스 연령 구성에 오염된다. `Q`는 연령 무관이라
  앵커가 흔들리지 않고 타입 간 동등성이 어느 모수에서든 성립한다.
- 클램프를 감쇠 뒤에 두면 앵커 p99를 크게 넘는 콘텐츠(매핑값 100 초과)가 오래된 뒤에도 부당하게
  높은 점수를 유지한다(`base=107 × 0.5 = 54` vs 올바른 `100 × 0.5 = 50`).
  `base ∈ [0,100]`이어야 "품질 백분위 × 신선도"라는 의미가 성립한다.

### 1-1. 출력 매핑과 소수점 노출 (2026-07-30~)

`score`(정수)는 랭킹 경로(`is_beauty AND (metric_timeliness='timely' OR NULL)`)에서 실측
p05=5·p50=23·p90=44·p99=60.8·**max=76**이었다 — 1등이 76점에 그쳐 0~100 척도의 상단(77~100)이
전혀 쓰이지 않았다. `analytics.hype_score_output()`이 `raw`를 **타입 무관 단일 앵커 세트**로 한 번
더 재매핑해 이 문제를 편다 — 매핑 형태는 콘텐츠 Q 앵커·계정 앵커와 같은 4점 구간선형
(`p05→10·p50→45·p90→80·p99→97`, `[0,100]` 클램프)이고, 타입을 다시 구분하지 않는다(타입
정규화는 `raw`를 만드는 `Q` 기준 앵커가 이미 끝냈으므로).

**정수 반올림 대신 소수 4자리**로 노출하는 이유: 정수 반올림은 랭킹 경로 상위 54건을 4개
정수값으로 압축해 표시·정렬이 `short_code` 알파벳순에 지배되는 동점을 만든다(계정 쪽
`avg_hype_score`에서 이미 실측된 것과 같은 구조적 결함, §2). `hype_score_precise`는 이 동점을
없애면서 동시에 척도도 넓힌다. `contents.hype_score`(정수)는 **값·의미가 바뀌지 않는다** —
`round(raw)::bigint`로, 리팩터 전과 항등이다. 자리수 조정은 프론트 몫이므로 소수는 자르기만
하고(반올림 아님) 그대로 응답에 싣는다.

### NULL 규칙

| 조건 | 결과 |
|---|---|
| `likes` 또는 `comments`가 NULL | NULL |
| 릴스인데 `views`가 NULL | NULL (도달 축이 views를 쓴다) |
| **피드의 `views`가 NULL** | **정상** — 피드는 views를 쓰지 않는다 |

피드 게시물은 조회수가 **항상** NULL이다(CLAUDE.md 함정). 이 규칙이 그 사실을 흡수한다.

## 2. 계정 점수

[`analytics/views/10_account_detail.sql`](../analytics/views/10_account_detail.sql) `v_account_summaries`.

```
raw           = avg(콘텐츠 score)                  -- 최근 N개 창(v_account_recent), NULL 제외
표시(구)      = hype_account_score(raw)            -- 계정 앵커로 0~100 매핑 (정수, 값·의미 불변)
정렬(구, 폐기예정) = raw                            -- 반올림 전 값

precise_raw   = sum(콘텐츠 출력매핑값) / 최근창 크기  -- 고정 분모(2026-07-31~, 아래 (재신) 참조). sum은
                                                    창 콘텐츠 hype_score_output(hype_score_raw(...)) 합(NULL 무시)
표시=정렬(신) = hype_account_score_precise(precise_raw)  -- 새 앵커로 0~100 매핑, 소수 4자리로 자름
```

**(구) 표시값과 정렬 키가 분리되어 있었다.** 계정 앵커 최상단 구간이 `97 + 3·(raw−a99)/(a99−a90)`이라
raw 14점(상위 1% 전체)이 정수 97~100의 4개 값으로 압축된다 — 실측에서 ≥97점에 54개 계정이 4개 값을
공유해 동점이 `handle` 알파벳순으로 깨지며 상위권 순서를 지배했다. 그래서 표시는 `avg_hype_score`
(정수), 정렬은 `avg_hype_raw`(반올림 전)를 썼다.

콘텐츠 점수에는 같은 문제가 없다 — 11만 건이라 상위 1%가 1,100건 이상이고 정수 100개 구간에
충분히 퍼진다. 계정은 약 6,900개라 상위 1%가 54개뿐이었다.

**(신) 2026-07-30부터 `avg_hype_score_precise`(소수)가 표시·정렬을 겸한다** — 정수 자체가 없으니
정수 동점→알파벳순 지배 문제가 애초에 생기지 않는다. 입력(`precise_raw`)도 `raw`와 다르다 — 창
콘텐츠의 **출력 매핑까지 반영된** 점수(`hype_score_output(hype_score_raw(...))`)를 재료로 쓰고,
계정 앵커도 이 새 기준량에 맞춰 별도로 재적합했다(`hype_account_score_precise`, §3). `raw`·
`hype_account_score`·`avg_hype_raw`(구)는 **값·의미가 바뀌지 않는다** — was는 더는 이 둘을 정렬에
쓰지 않지만 다음 릴리스까지 컬럼 자체는 유지된다(§6).

**(재신) 2026-07-31부터 `precise_raw`의 집계가 평균에서 고정 분모 합으로 바뀌었다**(계정 점수
표본 하한 없음 결함 해소, §7 해소 항목·스펙
[2026-07-31-account-score-fixed-denominator-design](superpowers/specs/2026-07-31-account-score-fixed-denominator-design.md)).
구 `precise_raw`는 `avg()`라 분모가 창에 실제로 든 콘텐츠 수(`analyzed_count`)였는데, 창이
12로 꽉 찬 계정 41%가 likes/comments 수집 누락으로 점수산출 콘텐츠 <12건이었고, 그중 점수산출
1~2건뿐인 계정이 그 1~2건의 평균만으로 12건 채운 계정과 동일하게 평가돼 상위권에 섞였다(test
스택 실측 — 배경은 위 스펙 §1). 신 `precise_raw = sum(...) / analytics.recent-window`는 분모를
창 크기로 고정한다 — `sum()`이 NULL을 무시하므로 점수산출 불가 콘텐츠는 분자에 0 기여(=감점)가
되고, 표본이 적을수록 유리해지는 구조가 없어진다. 앵커도 새 raw 분포(전반적으로 하향 이동)에
맞춰 재적합했다(1.4856/23.6566/56.3961/77.0479 → **1.2417/19.4383/52.2401/74.0179**, §3).
창 전체가 점수산출 불가면 `sum()`도 NULL이라 `NULL/분모=NULL` — "창 전체 점수 불가 → NULL"
계약은 그대로 유지된다. `raw`·`hype_account_score`·`avg_hype_raw`(구, 여전히 단순 평균)는
이 변경과 무관 — 다음 릴리스 DROP 대상이라 재교정하지 않는다.

창 전체가 점수 불가면 신·구 네 컬럼(`avg_hype_score`·`avg_hype_raw`·`avg_hype_score_precise`) 모두 NULL이다.

## 3. 상수 — 값·근거·표류 위험

기준값의 **단일 소스는 함수 내 `COALESCE` 기본값**이다. `app_setting`은 재배포 없는 런타임
오버라이드용이며 운영에는 행이 없다(= 함수 기본값이 곧 운영값). Flyway 시드를 넣지 않는다 —
넣으면 정본이 둘이 된다.

| 상수 | 값 | 역할 | app_setting 키 | 적합 모수 / 근거 | 표류 |
|---|---|---|---|---|---|
| **B** (팔로워 완충) | 1000 | 분모가 0에 가까워지는 것을 막는다 | **없음(하드코딩)** | **근거 없음 — v1 유산** | ⚠️ 절대 팔로워 단위라 코퍼스가 자라면 표류한다 |
| `e0` | 0.01 | 릴스 참여 정규화 | `analytics.hype-reels-e0` | v2.1 재적합 — 릴스 팔로워당 참여 중앙값 ≈0.0094 | 미점검 |
| `f0` | 0.03 | 피드 참여 정규화 | `analytics.hype-feed-f0` | v2 원값 | 미점검 |
| `wr` / `we` | 1 / 1 | 도달:참여 가중 | `analytics.hype-reach-weight` / `-engage-weight` | v2 결정 — 1:1 | — |
| 댓글 가중 | ×3 | 댓글이 좋아요보다 무겁다 | 없음(하드코딩) | v2에서 "유지" 결정. 댓글유도·경품 게시물 과대평가 엣지를 알고도 범위 밖으로 둔 것 | — |
| `halflife` | 14일 | 신선도 반감기 | `analytics.hype-fresh-halflife-days` | v3에서 유지 결정 — 서비스가 hypenow이므로 신선도를 강하게 반영 | — |
| 콘텐츠 앵커 (릴스) | `0.1373 / 1.3798 / 4.5716 / 10.3883` | `Q`→0~100 | `analytics.hype-anchor-q-reels-{p05,p50,p90,p99}` | **전체 서빙 코퍼스**의 `Q` 분위수 | 재적합 절차 있음(§5) |
| 콘텐츠 앵커 (피드) | `0.0447 / 0.6135 / 1.6320 / 3.0144` | 같음 | `analytics.hype-anchor-q-feed-{...}` | 같음 | 같음 |
| **콘텐츠 출력 앵커** (2026-07-30~) | `5 / 23 / 44 / 60.8` | `raw`(hype_score 정수와 같은 연속값)→0~100 재매핑, **타입 무관 단일 세트** | `analytics.hype-anchor-out-{p05,p50,p90,p99}` | **랭킹 경로**(`is_beauty AND (metric_timeliness='timely' OR NULL)`) 실측 분포(n=5,321·재확인 5,683) — 발굴 목록·랭킹 API가 둘 다 이 모수의 부분집합 | 재적합 절차 있음(§5) |
| 계정 앵커 (구) | `1.0833 / 12.8333 / 31.2000 / 44.8600` | `raw`→0~100 | `analytics.hype-anchor-acct-{...}` | **0점 제외** 계정 모수. 전량으로 잡으면 p05=0이라 `NULLIF`로 전 계정이 NULL이 된다 | ⚠️ 이미 표류 중. 다음 릴리스 드롭 후보(§6) |
| **계정 소수 앵커** (2026-07-31 재적합) | `1.2417 / 19.4383 / 52.2401 / 74.0179` (구 2026-07-30값 `1.4856 / 23.6566 / 56.3961 / 77.0479`) | `precise_raw`(콘텐츠 출력 매핑 반영 창 점수 합 / 고정 분모)→0~100 | `analytics.hype-anchor-acct-precise-{...}` | **0점 제외**(및 반올림 시 0이 되는 `raw<0.5`도 제외) 계정 모수 — 2026-07-31 `precise_raw` 집계를 평균→고정 분모 합으로 바꾸며 raw 분포가 하향 이동해 재적합(계정 표본 하한 없음 결함 해소, §7) | 재적합 절차 있음(§5) |
| 최근창 | 12 | 계정 점수 모수 | `analytics.recent-window` | 뷰가 직접 읽는다 | — |

매핑은 네 앵커를 `p05→10 · p50→45 · p90→80 · p99→97`로 잇는 구간선형이고, p99 초과는
`97 + 3·(x−a99)/(a99−a90)`이다.

**앵커 키 이름 주의**: v2.1까지 콘텐츠 앵커 키는 `hype-anchor-{reels,feed}-*`였고 기준량이 감쇠 후
`qf`였다. v3에서 기준량이 `Q`로 바뀌며 키를 `hype-anchor-q-*`로 개명했다 — **구 키에 옛 스펙 값을
넣어도 함수가 읽지 않는다**(조용히 망가지는 것을 막기 위한 의도적 개명).

## 4. 표면별 사용

| 표면 | 정렬 | 필터 |
|---|---|---|
| `/v1/contents` 랭킹 | `contents.hype_score_precise DESC NULLS LAST, short_code`(2026-07-30~, 표시도 동일 컬럼) | `is_beauty AND (metric_timeliness='timely' OR NULL)` — `late_backfill`·`immature` 제외 |
| `/v1/influencers?sort=hype` | `account_summaries.avg_hype_score_precise DESC NULLS LAST, handle`(2026-07-30~, 표시도 동일 컬럼) | 계정 요약 보유 계정 |
| `v_content_metric_snapshots` | — | as-of 조회. 감쇠 기준 시각이 `now()`가 아니라 `captured_at` |

`v_contents`는 감쇠 기준을 `now()`로 잡으므로 **점수가 조회 시점마다 재계산된다**(스냅샷 저장이 아니다).
미러 테이블 `contents.hype_score`는 마지막 미러 시점의 값이다.

**expand-contract 상태(2026-07-30~)**: `contents.hype_score`(bigint)·`account_summaries.avg_hype_score`
(bigint)·`account_summaries.avg_hype_raw`(numeric, 구 정렬 키)는 값·의미가 이번 릴리스에서 바뀌지
않았고 코드도 여전히 참조한다(구 대시보드 `/dashboard`·`/posts/{shortCode}` 등 프론트 전환 전
잔존 소비자). was의 표시·정렬은 전부 `_precise` 컬럼으로 옮겼으므로, 참조가 완전히 끊기는
**다음 릴리스에서 이 세 컬럼을 DROP한다**(소수 표시값이 정렬 키도 겸해 raw는 더는 필요 없다).

## 5. 재적합 절차

앵커는 모집단 분포에 맞춘 값이라 코퍼스가 자라면 낡는다. 재산출은
[`analytics/check/hype-anchor-refit.sh`](../analytics/check/hype-anchor-refit.sh) — **읽기 전용**.

- 대상 DB는 **crawler**(분석 뷰가 사는 곳). `coverage.sh`(analysis DB)와 대상이 다르다.
- 산출값을 `02_serving.sql`·`10_account_detail.sql`의 `COALESCE` 기본값에 반영 → 뷰 재적용 → 미러.

이 스크립트가 존재하는 이유: v2.1에서 **릴스만 재적합하고 피드는 v2 원값을 남겼는데, 스펙에 재현
절차가 없어서 아무도 그 누락을 검증할 수 없었다.** 그 결과 피드 앵커 p50이 실제 분포의 절반 수준으로
느슨하게 남아 피드 고득점 비율이 릴스의 약 2배가 됐다(발굴 목록 피드 편향의 직접 원인).

## 6. 배포 상태

| 환경 | 상태 |
|---|---|
| 운영(main) | **v2.1** — 감쇠 후 매핑, 피드 앵커 미재적합, 계정 점수 `round(avg)`. **타입 편향 살아 있음** |
| staging / test | v3 + 계정 척도 재교정 + 정렬 키 분리 |
| develop(이 PR) | 위 + **소수점 노출**(`hype_score_precise`·`avg_hype_score_precise`, 출력 매핑) |

**분석 뷰는 수동 적용이다** — PR 머지만으로 운영 점수가 바뀌지 않는다. 롤아웃 순서:
마이그레이션 → 뷰 적용(`--single-transaction`) → 미러 잡 → 스팟체크 → 프론트 통지.
점수 절대값과 순위가 동시에 움직이므로 프론트 통지가 필요하다.

## 7. 알려진 미해결

| 항목 | 내용 |
|---|---|
| **팔로워 정규화로 소형 계정 우대** | 상위 50 계정의 팔로워 중앙값이 전체 중앙값의 **0.57배**다(상위권이 평균보다 작은 계정으로 채워진다). 실측: `B`를 1200으로 올리면 비율 1.02로 규모 중립이 되고 타입 중립도 유지된다(0.856→0.867). **`B`를 빼면 악화된다**(비율 0.31 — 초소형 계정 폭주) |
| **`B`는 규칙이 아니라 상수** | 규모 중립을 주는 값은 대략 `0.3 × 전체 팔로워 중앙값`이다. `B`가 절대 단위 상수라 코퍼스가 자라면 자동으로 따라가지 않는다 — 앵커는 재적합되는데 `B`는 안 되는 비대칭 |
| ~~계정 점수 표본 하한 없음~~ | **2026-07-31 해소.** `avg_hype_score_precise`의 재료 `precise_raw`를 단순 평균(분모=창에 실제로 든 콘텐츠 수)에서 `sum(...) / analytics.recent-window`(분모=창 크기 고정)로 바꿔, 점수산출 콘텐츠가 창을 못 채운 계정이 자연히 감점되게 했다(수집 누락이어도 감점 — 화면에 안 뜨는 게시물은 유저에게 없는 것과 같다는 정합성 결정). 앵커도 새 raw 분포로 재적합(§3). 스펙 [2026-07-31-account-score-fixed-denominator-design](superpowers/specs/2026-07-31-account-score-fixed-denominator-design.md) |
| **`followers`가 게시 시점 아닌 현재값** | 성장한 계정의 옛 콘텐츠 `Q`가 과소평가된다. 적합·서빙이 같은 결함을 공유하므로 일관성은 유지된다 |
| **계정 순위는 사실상 게시 빈도 순위** | 창 스팬 구간별 감쇠 전 품질(`base`)이 35~43으로 평평한데 최종 점수는 10배 벌어진다. hypenow이므로 **의도된 동작으로 확정**했으나, 품질 신호가 거의 안 들어간다는 뜻이다 |
| 댓글 ×3 엣지 | 댓글유도·경품 게시물 과대평가 |

## 8. 이력

| 버전 | 변경 | 스펙 |
|---|---|---|
| v1 | 하드캡 `min(x,1)` 정규화 — **폐기** | — |
| v2 (07-20) | 하드캡 제거, 연속 로그 축, 타입별 앵커 | [2026-07-20-hype-score-v2-redesign](superpowers/specs/2026-07-20-hype-score-v2-redesign-design.md) |
| v2.1 (07-20) | 릴스 참여 축 분모를 조회수→팔로워로 교체(저조회수 뭉침 해소). 릴스 앵커만 재적합 | [2026-07-20-reels-hype-engage-follower-normalization](superpowers/specs/archive/2026-07-20-reels-hype-engage-follower-normalization-design.md) |
| v3 (07-30) | 감쇠를 앵커 매핑 뒤로, 클램프를 감쇠 앞으로. 앵커를 `Q` 기준·전체 서빙 코퍼스로 재적합. 계정 척도 재교정과 정렬 키 분리 동반 | [2026-07-30-hype-score-v3-decay-after-mapping](superpowers/specs/2026-07-30-hype-score-v3-decay-after-mapping-design.md) |
| v3 + 소수점 노출 (07-30) | 콘텐츠 출력 매핑(`hype_score_output`) 신설·타입 무관 단일 앵커(랭킹 경로 실측). `hype_score_precise`·`avg_hype_score_precise`(둘 다 소수 4자리) 신설, was 표시·정렬을 이 컬럼들로 이전. 구 정수 컬럼 3종은 값·의미 불변·다음 릴리스 드롭 대상 | [2026-07-30-hype-score-v3-decay-after-mapping §10](superpowers/specs/2026-07-30-hype-score-v3-decay-after-mapping-design.md) |
| 계정 점수 고정 분모 (07-31) | `precise_raw` 집계를 단순 평균(분모=창에 실제로 든 콘텐츠 수)에서 `sum(...) / analytics.recent-window`(분모=창 크기 고정)로 교체 — 점수산출 콘텐츠가 창을 못 채운 계정(수집 누락 41%)이 자연히 감점되도록 해 계정 점수 표본 하한 없음 결함 해소. 계정 소수 앵커 재적합(§3) | [2026-07-31-account-score-fixed-denominator-design](superpowers/specs/2026-07-31-account-score-fixed-denominator-design.md) |

### 실측으로 폐기된 안 (재도입 금지)

- **피드 앵커만 timely 모수로 재적합** — 적합 모수에서는 격차가 2.2배→1.33배로 줄지만 실서빙
  모수에서 피드가 릴스보다 낮아지는 **과잉교정으로 뒤집힌다**.
- **반감기 튜닝으로 타입 편향 잡기** — ≥70점 비율의 타입 비가 `halflife` 14~90일 전 구간에서
  0.81~0.90 상수다. 타입 중립은 v3의 구조 변경이 만든 것이고 반감기와 무관하다.
- **무감쇠(신선도 제거)** — 발굴 목록 상위 50이 피드 100%로 도배되고 기존 상위 50과 2/50만 겹친다.
  활동을 멈춘 계정의 옛 대박 게시물이 최근창에 영구히 박혀 평균을 밀어올린다.
- **`B` 제거** — §7 참조. 초소형 계정이 폭주해 악화된다.
- **계정 소수 앵커를 `hype_account_score`(구) 자체의 기본값 교체로 구현** — 문구상 자연스러워
  보이지만, `avg_hype_score`(bigint, 값·의미 불변 약속)가 `hype_account_score(avg_hype_raw)`로
  정의돼 있어 앵커를 바꾸면 그 값도 함께 바뀐다. 별도 함수(`hype_account_score_precise`)·별도
  app_setting 키(`hype-anchor-acct-precise-*`)로 완전히 분리해야 구 컬럼이 안 깨진다.
