# 성과 요약 통계 왜곡 가드 — 설계

> 상태: 🟢 활성

## 1. 배경

인플루언서 리포트의 `perf_summary`(AI 성과 요약)는 계정 요약의 **단순 평균**만 근거로 삼는다
(`avg_views`·`avg_er_pct`·`avg_likes`·`avg_comments`·`avg_hype_score` + `trend_direction`).
중앙값·분위수·표본 수는 파이프라인 어디에도 없어, "릴스 2건 중 1건이 터진" 계정이 꾸준한 계정과
같은 문장을 받는다.

프롬프트는 이미 구체 수치 인용을 금지하고 "높은 편/낮은 편" 같은 수준 표현만 허용한다
([GeminiAccountSynthesizer.java](../../../analytics/src/main/java/com/celfit/analytics/llm/GeminiAccountSynthesizer.java)).
그러나 **그 판정의 입력이 평균이면 표현만 흐려지고 왜곡은 그대로 통과한다.** 이 설계는 판정의
입력을 바꾸는 것이 목적이다.

## 2. 실측 근거 (2026-07-30, 운영 DB 읽기 전용)

계정 7,033개 기준.

| 관측 | 수치 | 함의 |
|---|---|---|
| `analyzed_count` 12+ | **95.4%** (6,708) | 계정 단위 표본 부족은 희소 — n<3은 0.9%(65개) |
| 혼합 계정(릴스+피드) | **66.8%** (4,697) | 릴스 비중 p10 = 0.10 → 12건 창에 릴스 1건 |
| top1 조회수 점유율 | p50 **0.466** / p75 0.693 / p90 0.962 | 절반의 계정에서 조회수 절반이 게시물 1건 |
| top1 점유율 (모수 3+) | p50 0.413 / p75 0.587 / p90 **0.781** | |
| avg/median 비 | p50 1.36 / p90 6.07 / max 2,175 | |
| 창 길이 | p50 **50일** / p90 **417일** / max 3,291일 | 365일+ 11.7%, 90일+ 34.6% |
| 성장세 절반당 1건 | 1.4% (97) | 성장세 왜곡은 건수가 아니라 시간 축 |
| 좋아요 비공개(-1) 원본 | **30.9%** (196,143/635,511) | 뷰에서 NULL 처리는 옳으나 모수가 조용히 줄어듦 |

실측이 **폐기시킨 초기 가설 2건** (재도입 금지):

- `views > 0` 필터의 상향 편향 — 릴스 43,433건 중 `views = 0`은 **1건**뿐이고 그마저
  likes·comments까지 0인 수집 실패 의심 건. 영향 계정 6,014개 중 1개. 실익 없음.
- `likes = -1` 센티널 혼입 — [00_base.sql](../../../analytics/views/00_base.sql)의
  `NULLIF(..., -1)`에서 이미 NULL 처리됨. 혼입 없음.

### 핵심 재정의

**표본 문제는 계정 단위가 아니라 지표 단위다.** `analyzed_count`는 12로 꽉 차 있는데 그 12건 중
릴스가 1건인 계정이 실존한다(예: `1004ya486`, `155.8kg`, `0nlymy0wn_`, `0_0_na_hyun`, `171._yun` —
모두 `analyzed_n=12`, 조회수 관측 1건). 조회수 평균의 실질 모수는 1이다.

**한 건 지배는 예외가 아니라 기본값이다.** 중앙값 0.466이면 플래그로 경고할 대상이 아니라
판정 기준을 평균에서 중앙값으로 옮겨야 하는 사안이다.

## 3. 설계

3층으로 나누고, **임계값은 뷰에 굳히지 않는다.** 운영 뷰 적용은 수동 런북이라 SQL에 등급을
박으면 임계값 조정마다 운영 DDL이 붙는다. 뷰는 원시 수치만, 등급은 Java 상수.

### 3-1. 데이터 층 — `v_account_summaries` 컬럼 9개 추가

[analytics/views/10_account_detail.sql](../../../analytics/views/10_account_detail.sql)

| 컬럼 | 타입 | 정의 |
|---|---|---|
| `views_sample_count` | int | 창 내 `views IS NOT NULL` 건수 (사실상 릴스 관측 수) |
| `likes_sample_count` | int | `likes IS NOT NULL` 건수 |
| `comments_sample_count` | int | `comments_count IS NOT NULL` 건수 |
| `reels_count` | int | `content_type = 'REELS'` 건수 |
| `feed_count` | int | `content_type = 'FEED'` 건수 |
| `median_views` | bigint | `percentile_cont(0.5)` — `views IS NOT NULL` 대상 |
| `median_er_pct` | numeric | ER 중앙값 (분모는 기존 `avg_er_pct`와 동일 산식) |
| `top_views_share_pct` | int | `max(views) / sum(views) × 100` — 최상위 1건의 점유율 |
| `window_span_days` | int | `max(uploaded_at) − min(uploaded_at)` 일수 |

기존 컬럼의 정의는 **바꾸지 않는다**(`avg_views`의 `FILTER (WHERE views > 0)` 포함 —
§2에서 폐기된 가설).

미러 테이블은 [V44 마이그레이션](../../../analytics/src/main/resources/db/migration/analysis)에서
`ADD COLUMN`만 수행(expand 단계). `contract-analysis`의 `AccountSummary` record에 대응 필드를
추가하면 [MirrorJob](../../../analytics/src/main/java/com/celfit/analytics/mirror/MirrorJob.java)이
필드명을 snake_case로 변환해 자동 INSERT한다 — 배선 코드는 없다.

> 구현 시 확인: `MirrorJob.toSnakeCase()`가 새 필드명을 위 컬럼명으로 정확히 변환하는지
> 단위 테스트로 못 박을 것. 이름이 어긋나면 미러가 런타임에 깨진다.

### 3-2. 판정 층 — `PerfConfidence` (Java, 결정론적)

임계값은 §2 실측 분위수에 붙인다.

**지표별 모수 게이트** (가장 먼저 적용):

| 실질 모수 | 등급 | 문구 처리 |
|---|---|---|
| ≤ 2 | `INSUFFICIENT` | 해당 지표 문장 **생략** |
| 3–5 | `WEAK` | 톤 연화 — "표본이 적어 단정하기 어렵지만" |
| ≥ 6 | `OK` | 그대로 |

**한 건 지배** (모수 3+ 계정에만 적용 — 모수 1이면 점유율이 자동 100%라 위 게이트와 중복):

- `top_views_share_pct >= 75` → 평균 서술을 버리고 **대표작 1건 관점**으로 전환.
  모수 3+ 계정의 실측 p90 = 0.781이므로 상위 약 12%.

**성장세 유효성** (건수가 아니라 시간 축):

| `window_span_days` | 판정 | 문구 처리 | 해당 비율 |
|---|---|---|---|
| > 90 | `UNAVAILABLE` | `trend_*` 4컬럼을 프롬프트 입력에서 제거 — 성장세 문장 **생략** | 34.6% |
| ≤ 90 | `OK` | 그대로 | 65.3% |

> **3차 test 실측(2026-07-30)으로 2단계로 단순화**. 초판은 `TOO_LONG`(>365일, 값 제거)과
> `LONG_SPAN`(90–365일, 값은 남긴 채 "장기간에 걸친 변화처럼 완만하게 표현하라"는 지시로만
> 통제) 3단계였다. 그런데 3차 실측 30개 표본에서 `LONG_SPAN` 구간이 그대로 새어나왔다 —
> `0205s.y`(창 207일)가 "최근 게시물들의 조회수가 급격히 상승하는 뚜렷한 우상향 추세를
> 보입니다", `02_10.13`(157일)이 "최근 게시물 반응이 이전보다 상승하는 추세입니다"(완화
> 없이 단정), `119irl`(201일)이 "조회수 흐름은 상승세를 보이고 있으나"(완화 없이 방향성
> 단정)로 나왔다 — `0205s.y`의 문구는 207일에 걸친 12건을 최근 급등으로 읽히게 만드는,
> 이 트랙이 애초에 잡으려던 왜곡 그 자체다. 반면 값을 아예 제거한 `TOO_LONG`·always-strip
> 7컬럼은 누출 0건이었다(§3-3-1의 1차 실측과 일치) — **"프롬프트 입력에서 제거한 항목은
> 지켜지고, 값을 남긴 채 지시로만 통제한 항목은 새어나온다"**는 결론이 세 번째 실측에서도
> 재확인됐다.
>
> **결정**: `LONG_SPAN`을 폐지하고 90일 초과 전 구간을 `TOO_LONG`과 동일하게(`trend_*` 4컬럼
> 제거) 처리한다. 두 상태가 완전히 같은 동작(값 제거)을 하게 됐으므로 `TrendValidity`도
> `OK`/`UNAVAILABLE` 2상태로 단순화한다(`PerfConfidence.TrendValidity`) — 같은 동작을 하는
> 상태를 두 개로 나눠 두는 건 불필요한 복잡도다.
>
> **트레이드오프(의도된 수용)**: 창 90일 초과가 실측 34.6%이므로, 이전에는 연화된 문장이라도
> 받았던 그만큼의 계정에서 성장세 문장이 완전히 사라진다. 정보 손실이지만, 부정확한(또는
> 실측처럼 왜곡된) 추세 서술보다 아예 없는 게 낫다는 판단이다 — 이 트랙의 간판 원칙("금지는
> 입력 제거로 강제한다", §3-3-1)을 성장세 유효성 판정에도 예외 없이 적용한 결과다.

**포맷 비교 가능성**: `reels_count < 3` 또는 `feed_count < 3` → 포맷별 반응 차이 언급 금지.

### 3-2-1. 알려진 한계 — 입력 제거를 적용할 수 없는 잔여 항목 (미해결, 3차 test 실측)

`LONG_SPAN`과 달리 `WEAK` 등급·한 건 지배(dominance) 프레이밍은 같은 실측에서 결함이 확인됐지만
**이번에 고치지 않는다** — 값을 제거하는 검증된 수단 자체를 적용할 수 없는 항목들이기 때문이다.

- **`WEAK` 헤지 누락**: `0o0.soni`는 헤지 표현("표본이 적어 단정하기 어렵지만") 자체가 문구에
  없었고, `000bk`는 좋아요 서술에만 헤지가 빠졌다(다른 지표엔 붙음). `WEAK`는 톤 연화가
  목적이라 값(표본 3~5건의 실제 수치)이 있어야 문장이 성립한다 — 값을 지우면 `INSUFFICIENT`
  (생략)와 구별이 없어져 등급 자체가 무의미해진다. 즉 "값은 남기고 표현만 다듬어라"는 지시
  의존적 통제를 이 등급에서는 포기할 수 없다.
- **dominance 프레이밍 미반영**: `00.young__da`(top1 조회수 점유율 85% → `singlePostDominance()`
  true)가 "계정 성장을 견인"처럼 계정 전체 추세로 서술했다. 지침("대표작 1건이 끌어올린 구조라는
  관점으로 써라")은 `median_views`·게시물 목록(posts)의 원값이 근거로 필요해 그 값들을 제거할 수
  없다 — 제거하면 dominance 서술 자체가 근거를 잃는다.

**향후 선택지**: 두 항목 다 "프롬프트 지시"가 아니라 "코드가 결정론적으로 문장을 조립"하는
방식(예: 템플릿에 표본 수·대표작 여부를 코드가 직접 끼워 넣고 LLM은 다듬기만 하는 구조)으로
옮기면 같은 문제(지시 무시)를 근본적으로 피할 수 있다 — 이번 트랙 범위 밖으로 남긴다.

### 3-3. 문구 층 — 프롬프트 주입

`INSTRUCTIONS_TEMPLATE`에 계산된 신뢰도 지침 블록을 주입하고, **수준 판정의 근거를
`avg_*` → `median_*`으로 교체**한다.

**부작용 차단**: `summary`는 `SELECT * FROM account_summaries`로 통째로 프롬프트에 들어간다
([AccountAnalysisJob.java](../../../analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java)).
새 컬럼을 그대로 두면 `views_sample_count=1` 같은 **내부 수치가 LLM에 노출되어 문구에 인용될
여지**가 생긴다. 판정에 쓴 뒤 프롬프트 입력 맵에서는 제거한다.

#### 3-3-0. always-strip 재정의 — median을 always-strip에 넣어 간판 변경이 무력화됐던 사고 (2026-07-30)

초판 구현은 뷰가 새로 추가한 9컬럼(§3-1) 전부를 판정 재료 목록(`PerfConfidence.
CONFIDENCE_COLUMNS`)에 넣고, 이 목록을 그대로 "프롬프트에서 항상 제거할 컬럼"으로도 재사용했다.
그런데 이 9컬럼에는 `median_views`·`median_er_pct`도 포함돼 있었다 — 그 결과 **이 트랙의 간판
결정("수준 판정의 근거를 평균에서 중앙값으로 옮긴다", §2)이 실제로는 전혀 적용되지 않는 상태가
됐다.** 지침 문구는 "median을 우선 근거로 삼고 NULL이면 avg로 대신하라"고 지시하는데, LLM은
median 값을 애초에 입력에서 보지 못하니 항상 avg 폴백으로 떨어진다. test 실측에서 `777minseo`
(top1 조회수 점유율 81% — 한 건이 조회수 대부분을 끌어올린 극단 사례)가 "조회수 성과가 매우
높은 편"으로 나온 게 이 증상이다 — 근거가 median이었다면 이렇게 나오지 않았을 것이다.

**재정의**: always-strip은 판정 전용 내부 입력 **7개**로 한정한다 — `views_sample_count`·
`likes_sample_count`·`comments_sample_count`·`reels_count`·`feed_count`·
`top_views_share_pct`·`window_span_days`. `median_views`·`median_er_pct`는 여기서 뺀다 —
이 둘은 "내부 판정 수치"가 아니라 **판정의 정당한 근거**이므로, 노출 자체가 문제였던 적이 없다
(구체 수치 인용 금지는 별도 프롬프트 규칙이 이미 담당한다). `PerfConfidence.CONFIDENCE_COLUMNS`가
이 7개 목록의 정본이고, `AccountAdCanon.INTERNAL_CONFIDENCE_COLUMNS`가 그대로 재사용한다.

**median을 "선택지"가 아니라 "유일한 근거"로 만든다**: median이 존재하는데 대응 avg도 함께
보이면 LLM이 avg를 골라 쓸 여지가 남는다 — "지켜주길 바라는 지시"가 아니라 "입력 제거로 강제"
한다는 §3-3-1 실측 보완의 원칙을 여기에도 적용한다. `PerfConfidence.excludedSummaryKeys()`가
다음을 조건부로 계산한다:

- `median_views`가 non-NULL이면 `avg_views`·`views_per_follower`를 제거 대상에 추가한다.
- `median_er_pct`가 non-NULL이면 `avg_er_pct`를 제거 대상에 추가한다(대응 평균 키는 이거
  하나뿐 — `views_per_follower` 같은 파생 키가 없다).
- median이 NULL(조회수 관측이 없는 계정 등)이면 대응 avg는 그대로 남긴다 — 폴백 경로가
  실제로 쓰인다(실측: `median_views` NULL 949/6,653건, `median_er_pct` NULL 148건).
- 조회수가 `INSUFFICIENT`(모수 ≤2)면 `median_views`가 존재하더라도 **함께 제거**한다(§3-3-1
  기존 조건부 제거 규칙과 합성) — 모수가 부족하면 median도 판정 근거가 될 수 없다(모수 1인
  계정은 median이 그 1건 값 그대로다). 좋아요·댓글은 대응 median 컬럼 자체가 없어 이 규칙과
  무관하다.

지침 문구도 "median 우선, NULL이면 avg 폴백"이라는 선택지 표현을 걷어내고 "median이 입력에
있으면 그게 근거, median 없이 avg만 있으면 그게 근거"로 단순화했다 — 입력에 무엇이 있는지가
곧 근거이지, 모델이 선택할 여지를 주지 않는다.

### 3-3-1. test 실측 보완 (2026-07-30) — 지시만으로는 안 지켜진다

test 스테이징에 배포해 계정 5개로 실제 문구를 뽑아본 결과, **판정 로직(§3-2)은 정확했는데 LLM이
지침의 절반을 무시했다.** 결정적 대비:

- **프롬프트 입력에서 제거한 것**(당시 always-strip 목록 — §3-3-0 재정의 이전에는 신 컬럼 9개
  전부)**은 100% 지켜졌다** — 내부 수치 누출 0건.
- **"보이지만 언급하지 마라"로 지시만 한 것은 안 지켜졌다**:
  - `180.e_cm`(`window_span_days=691` → `TrendValidity.TOO_LONG`, 성장세 서술 전면 금지)인데
    생성 문구가 "최근 전체적인 지표는 하향 곡선을 그리고 있습니다" — `trend_direction`·
    `trend_change_pct`가 입력에 그대로 있어서 읽고 썼다. 이 계정은 실측 11.7%(§2 표, 약 823개)의
    표본이다.
  - `0_tsuki2`(`views_sample_count=2`, `reels_count=2` → 조회수 서술·포맷 비교 둘 다 금지)인데
    "릴스와 피드 게시물 간의 반응 차이는 뚜렷하게 나타나지 않으며" — "차이가 없다"는 **회피
    서술로 금지를 우회**했다. 포맷을 언급하지 말라는 취지였는데 부정형 비교도 비교 진술이라는
    걸 명시하지 않아 빠져나갔다.
  - `0n_neww`(조회수·좋아요 모수 3 → `WEAK`, 톤 연화)는 지정 문구는 넣었으나 **순서가 거꾸로**다:
    "안정적인 흐름을 보입니다. 릴스 콘텐츠의 경우 표본이 적어 단정하기 어렵지만…" — 먼저 단정하고
    나중에 발뺌하는 모양이고, 모수가 충분한 댓글(12건)까지 같은 완화구에 묶였다.

**원칙**: 프롬프트 지침은 모델이 "지켜주길 바라는" 요청이지 강제가 아니다 — 데이터가 입력에
있으면 "언급하지 마라"는 텍스트 지시를 무시하고 읽어서 쓸 수 있다는 게 이번 실측의 핵심이다.
**금지는 입력 제거로 강제하고, 텍스트 지시는 "완화·순서·회피 방지"처럼 데이터 자체는 필요한
경우에만 보조 수단으로 쓴다.**

이 실측에 따라 §3-2 판정을 프롬프트 입력 제거에 조건부로 연결했다(`PerfConfidence.
excludedSummaryKeys()`·`excludedPostFields()`, `AccountAdCanon.withConfidence`·
`withPostConfidence`):

- `TrendValidity.TOO_LONG` → `trend_direction`·`trend_change_pct`·`trend_older_avg`·
  `trend_newer_avg` 4컬럼을 프롬프트 요약에서 제거. `LONG_SPAN`은 톤만 연화하므로 그대로 둔다.
  (**이후 갱신**: 이 문단은 1차 실측 시점의 상태를 기록한 것이다. 3차 실측에서 `LONG_SPAN`
  구간도 새어나온 게 확인돼 §3-2에서 `LONG_SPAN`을 폐지하고 `TOO_LONG`과 통합했다 —
  `TrendValidity`는 이제 `OK`/`UNAVAILABLE` 2상태다. 아래 서술도 그 시점 기준이다.)
- 지표 등급이 `INSUFFICIENT`(모수 ≤2)면 그 지표의 계정 집계 키를 제거 — 조회수는 `avg_views`·
  `views_per_follower`·`median_views`(§3-3-0 재정의 후 median도 always-strip이 아니므로 여기서
  명시적으로 같이 지운다), 좋아요는 `avg_likes`, 댓글은 `avg_comments`. `WEAK`(3~5)는 톤 연화가
  목적이라 값이 필요하므로 제거하지 않는다.
- **게시물 목록(posts)도 동일 원칙을 적용한다** — `views_sample_count` 등이 INSUFFICIENT면
  집계 키를 지워도 `posts`의 게시물별 원값(`views`·`likes`·`comments`)이 남아 있으면 모수
  2건짜리 지표라도 목록에서 수준을 유추해 서술할 수 있다(`0_tsuki2` 사례가 실제로 이 경로로
  보인다). `content_type`·`caption`·`sponsored`는 `contentSummary`·`adSummary`에도 쓰여
  절대 제거하지 않는다 — 지표별 원값(`views`/`likes`/`comments`)만 해당 지표가 INSUFFICIENT일
  때 제거한다.
- **포맷 비교 금지 지침 강화** — `content_type`은 다른 카피 항목에도 필요해 제거할 수 없다. 대신
  지침에서 "차이가 없다/뚜렷하지 않다/비슷하다" 같은 부정형 비교도 비교 진술이므로 금지라고
  명시하고, 포맷을 언급하는 문장 자체를 만들지 말라고 못박았다.
- **톤 연화 순서 교정** — `WEAK` 지침은 완화 표현("○○ 표본이 적어 단정하기 어렵지만")이 해당
  지표 서술 **앞**에 오도록, 그리고 어느 지표가 약한지 지표명을 담도록 다시 썼다(이전엔 "언급하되
  톤을 낮춰라"처럼 순서를 지정하지 않아 모델이 단정→발뺌 순으로 쓰고, 지표명도 없어 강한 지표까지
  묶어 연화했다).

`CopyRules.VERSION`은 이 수정으로 올리지 않는다 — 운영에는 아직 버전 1로 생성된 행이 없다(운영
미배포). test에 남은 버전 1 문구 5건은 test DB에서 직접 되돌린다(검증 담당 몫, 코드 변경 아님).

### 3-3-2. test 실측 보완 (2026-07-30) — email 컬럼 유출과 "카피 무관 컬럼" 제외 원칙

트랙 BB(PR #209)가 `account_summaries`에 `email`(인플루언서 소개글 정규식 파싱, 스펙
2026-07-30-influencer-email-from-bio) 컬럼을 추가했다. §3-3의 "부작용 차단" 원칙(`summary`가
`SELECT *`로 통째로 프롬프트에 들어간다)이 여기서 실제로 재현됐다 — `email`은 카피 생성 어디에도
쓰이지 않는데, `SELECT *` 구조 때문에 아무 코드 변경 없이도 자동으로 프롬프트에 실려 **실
연락처가 외부 LLM(Gemini) API로 전송**되고 있었다. `PerfConfidence.CONFIDENCE_COLUMNS`
(always-strip 7컬럼)는 판정 재료 목록이자 `dataIncomplete()`(§7) 판정 근거이기도 해서, `email`
처럼 판정과 무관한 컬럼을 여기 섞으면 "7개 전부 NULL=미러 갭"이라는 판정 기준이 오염된다(email은
정상 계정에서도 소개글 미기재·정규식 미매치로 흔히 NULL이라 판정 재료로 부적합하다).

그래서 판정 재료(`CONFIDENCE_COLUMNS`)와는 별개로 "카피 생성과 무관해 프롬프트에서 항상 제거할
컬럼" 목록(`AccountAdCanon.PROMPT_IRRELEVANT_COLUMNS`)을 신설했다 — 현재는 `email` 하나뿐이다.
`AccountAdCanon.withConfidence`가 만드는 프롬프트 입력 = always-strip 7컬럼 + 이 목록 +
`excludedSummaryKeys()`(조건부 제거)를 합성해서 제거한 결과다. **원칙**: `account_summaries`에
컬럼이 추가될 때마다 이 함정이 재발한다 — 새 컬럼이 카피 문구 생성에 쓰이지 않는다면 반드시
`PROMPT_IRRELEVANT_COLUMNS`(또는 판정에 쓰인다면 `CONFIDENCE_COLUMNS`)에 명시적으로 추가해야
한다. 이 결정을 코드 리뷰에만 의존하지 않도록, 프롬프트에 실리는 키 집합 전체를 하드코딩된
기대 목록과 대조하는 회귀 테스트(`AccountAdCanonTest`)를 두어 새 컬럼이 조용히 새는 것을 막는다.

## 4. 기존 문구 재생성

`AccountCopy`에는 버전 게이트가 **없다**. `ELIGIBLE_WHERE`의 재생성 조건은 ①분석 이력 없음
②`perf_summary IS NULL`(07-27 개편 흔적) ③새 게시물 + 쿨다운 경과 뿐이라, **새 게시물을 올리지
않는 계정은 문구가 영구 고정된다.**

**채택: `account_analyses.copy_version int NOT NULL DEFAULT 0` 추가 + `ELIGIBLE_WHERE`에
`OR latest.copy_version < CopyRules.VERSION`.**

`Synthesis.VERSION`이 이미 쓰는 관용구를 계정 카피에 적용하는 것이다. 대안인
`UPDATE account_analyses SET perf_summary = NULL` 일괄 실행은 운영 데이터를 지우는 파괴적
UPDATE이고 재생성이 실패한 계정은 문구가 빈 상태로 노출된다 — 버전 컬럼은 새 문구가 쓰일
때까지 기존 문구를 살려둔다. `ADD COLUMN`만이라 expand-contract 가드도 통과한다.

`CopyRules.VERSION = 1`. 기존 행은 기본값 0이라 전량(7,033) 재생성 대상이 되고 LLM 호출
7천 건이 붙는다. 배치 상한 30,000 유지 확정이라 한 배치에 소진된다. "플래그가 실제 발동하는
계정만" 좁히는 안은 창 길이 90일 초과만 34.6%여서 절감이 절반에 그쳐 복잡도 대비 이득이 작다.

## 5. 범위 밖 (별건으로 남김)

- **화면 수치는 여전히 평균이다.** 서빙 DTO를 바꾸지 않기로 했으므로 문구는 중앙값 기준으로
  보수적인데 옆 스탯 타일은 평균이다. 다음 단계에서 중앙값 병기를 프론트와 협의.
- **`analyzedCount`(=12)가 "샘플 개수"로 화면에 나간다**
  ([V2InfluencerReportAssembler.java](../../../was/src/main/java/com/celfit/was/v2/influencer/V2InfluencerReportAssembler.java)).
  조회수 모수가 1건인 계정에서도 12로 표시 — 오독의 직접 원인이지만 DTO 무변경 결정에 따라 유보.
- **광고 관측 비대칭**: `ad_marked`는 릴스의 `is_paid_partnership`만 반영하고 피드는 항상
  `false` 하드코딩([00_base.sql](../../../analytics/views/00_base.sql)). 광고 1건 이상 계정이
  19.1%로 잡히지만 혼합 계정 66.8%의 피드 광고는 구조적으로 안 잡혀, `adSummary`가 "광고 비중
  낮음"으로 기울 수밖에 없다.

## 6. 검증

- **SQL 하니스**: `analytics/test/10_*.test.sql`에 새 컬럼 케이스 추가 — 모수 1건 계정,
  top1 점유율 100% 계정, 창 길이 장기 계정, 피드 전용 계정(조회수 전무).
- **Java 단위 테스트**: `PerfConfidence` 등급 경계값(2/3/5/6, 74/75, 90 — `LONG_SPAN` 폐지 이후
  90일 초과는 전 구간 `UNAVAILABLE`), 미러 필드명↔컬럼명 매핑, 프롬프트 입력 맵에서 내부 컬럼이
  제거되는지(91일·366일 둘 다 `trend_*` 4컬럼 제거, 90일은 유지).
- **재생성 게이트**: `copy_version < VERSION`인 행이 후보로 잡히고 생성 후 현재 버전으로
  기록되는지.

## 7. 배포 순서 — 뷰 선적용 → 미러 → 분석 잡 (필수)

§3-1의 데이터 층(뷰 컬럼 9개 추가)은 Flyway가 아니라 [운영 런북](../../../analytics/README.md)에
따른 **수동 적용**이다. 반면 미러 테이블 컬럼(V44)은 Flyway로 자동 배포된다. 이 둘의 배포 경로가
다르다는 사실 자체가 순서를 어기면 실패하는 근본 원인이다.

> **정정(2026-07-30)**: 위 "뷰=수동 런북" 전제는 틀렸다 — main 배포 CD가 뷰를 자동 적용한다.
> 아래 실패 시나리오와 코드 가드는 여전히 유효하다. 상세는 이 섹션 끝의 정정 서브섹션 참고.

**필수 순서**: ① 뷰(`analytics/views/10_account_detail.sql`) 운영 DB에 수동 적용 → ② 미러
실행(`MirrorJob`, 뷰 컬럼을 읽어 `account_summaries`에 반영) → ③ 분석 잡(`AccountAnalysisJob`·
`ClaudeBurstRunner`) 실행. **V44(신 컬럼 `ADD COLUMN`) 배포는 이 순서와 별개로 아무 때나 가능** —
문제는 코드(마이그레이션)가 뷰보다 먼저 배포되는 것 자체가 아니라, **미러가 뷰보다 먼저 도는 것**이다.

### 순서를 어기면 벌어지는 일 (닫힌 결함, 이 설계가 막는 사고)

1. V44이 배포돼 `account_summaries`에 새 컬럼 9개가 생기지만 기본값이 없어 기존 행은 전부 NULL이다.
2. 뷰가 아직 안 올라간 상태에서 미러가 돌면, `MirrorJob.verifyColumns`가 뷰 컬럼과
   `AccountSummary` record 필드 불일치를 잡아 예외를 던지고 **미러 전체가 실패**한다.
3. 미러가 실패했으니 `account_summaries`의 기존 행은 그대로 남고, 새 컬럼 9개는 계속 NULL이다.
4. 이 상태에서 `AccountAnalysisJob`이 돌면 (가드가 없다면) 모든 계정이 `PerfConfidence`의 최대
   억제 등급을 받아 성과 문장이 거의 통째로 생략된 저품질 카피가 생성된다.
5. `AccountAnalysisWriter`가 그 저품질 카피를 `copy_version = CopyRules.VERSION`(현재 최신)으로
   찍는다. `ELIGIBLE_WHERE`의 재대상 조건(§4)은 "버전이 낮으면"이지 "이 버전으로 만든 문구가
   나쁘면"이 아니므로, **뷰를 나중에 올려도 이 행은 다시 후보로 잡히지 않는다** — 저품질 문구가
   최신 버전으로 영구 고정된다.

### 코드 가드가 막아주는 부분

`PerfConfidence.dataIncomplete()`(§3-2 확장)가 always-strip 7컬럼(`PerfConfidence.
CONFIDENCE_COLUMNS`, §3-3-0 재정의로 `median_views`·`median_er_pct`는 빠졌다) 전부 NULL/부재
상태를 감지한다. `views_sample_count`·`likes_sample_count`·`comments_sample_count`·
`reels_count`·`feed_count`는 뷰에서 `count(*) FILTER (...)`로 계산되는데, 이 집계 함수는 매치되는
행이 0건이어도 SQL NULL이 아니라 정수 0을 반환한다 — 분석 이력이 하나라도 있는 계정(`GROUP BY`가
성립하는 조건)이라면 이 5개 컬럼은 **정상 운영에서 절대 NULL이 될 수 없다**. 예를 들어 피드 전용
계정은 `views_sample_count=0`·`reels_count=0`·`feed_count=12`처럼 값이 채워지지 NULL이 되지
않는다. 따라서 7개 전부 NULL은 "표본이 진짜 없는 계정"에서는 발생 불가능하고, 미러가 이 컬럼들에
아무것도 쓰지 못한 배포 과도기(위 시나리오 3단계)에서만 성립한다. (`median_views`·
`median_er_pct`는 정상 운영에서도 NULL일 수 있어 이 감지에서 뺐다 — 5개 count 컬럼만으로 이미
불가능성이 보장돼 median 2개를 더해도 판정이 달라지지 않는다.)

`AccountAnalysisJob.analyzeOne`·`ClaudeBurstRunner.exportAccounts`는 (`AccountAdCanon.withConfidence`
경유로) 이 신호를 받으면 **카피를 생성하지 않고 건너뛴다** — 아무것도 안 쓰는 게 저품질 문구를
최신 버전으로 찍어 영구 고정하는 것보다 안전하다(기존 문구는 그대로 서빙된다). 이 스킵은 뷰가
올라갈 때까지 매 배치에서 같은 계정이 후보로 다시 잡히는 상태를 만들지만, 과거 무한 재대상 루프
사고(is_beauty NULL 재분류, 07-21)와는 원인이 다르다 — 그때는 재대상 조건이 결정론적으로 절대
해소되지 않아 문제였고, 여기는 뷰 적용 한 번으로 다음 미러·배치부터 자연히 해소된다. 스킵 건수는
배치당 집계 WARN 로그로 남아, 운영자가 "뷰 적용을 안 했다"는 걸 알아챌 유일한 신호가 된다.

**코드 가드가 못 막는 부분**: 뷰를 아예 올리지 않으면 스킵이 무한히 반복된다 — 가드는 사고를
안전하게(저품질 영구 고정 없이) 견디게 할 뿐, 뷰 적용 자체를 대신해주지 않는다. 배포 순서(①→②→③)
준수는 여전히 운영자의 책임이다.

### 정정 (2026-07-30) — 뷰 적용은 CD 자동화다, 수동 런북이 아니다

위 "§3-1의 데이터 층은 Flyway가 아니라 운영 런북에 따른 수동 적용"이라는 전제는 **틀렸다.** 실측:

- `.github/workflows/cd.yml`에 "분석 뷰 적용 (raw DB, 멱등)" 스텝이 있고, **07-20부터 main 배포마다
  `analytics/views/*.sql`을 자동 적용**한다(07-30 최적화로 해시가 동일하고 뷰가 이미 있으면 스킵).
- 트랙 Y 배포(main `5aa340cc`)에서 이 스텝이 08:45:52 UTC에 실제 실행됐고, 서버의
  `~/deploy/views.sha256`가 `origin/main`의 뷰 파일 해시와 정확히 일치함을 확인했다.

즉 **"운영자가 뷰 선적용을 잊는다"는 시나리오는 main 배포(CD) 경로에서는 발생하지 않는다** — 뷰
적용이 배포 파이프라인에 편입돼 사람이 순서를 놓칠 여지가 없다.

**다만 위 실패 시나리오와 코드 가드가 무의미해진 것은 아니다.** 아래 경로에서는 여전히 미러가
뷰보다 먼저 도는 상황이 성립한다:

- CD의 뷰 적용 스텝 자체가 실패하는 경우(멱등 적용이라도 DDL 에러·권한 문제 등은 배제되지 않는다).
- 운영자가 수동으로 미러를 먼저 트리거하는 경우(analytics 어드민 `/ui`의 잡 트리거).
- 뷰 적용 스텝의 순서·존재 여부가 main과 다를 수 있는 test/dev 배포 경로(`cd-test.yml`).

따라서 `PerfConfidence.dataIncomplete()` 가드와 위 실패 시나리오 서술(①~⑤) 자체는 그대로 유효하다
— 고쳐야 했던 것은 "뷰 적용이 사람 손에 달려 있다"는 전제뿐, 가드를 없애자는 뜻이 아니다.

## 8. test 실측 보완 (2026-07-30) — `LlmGuard` 전역 규칙과의 상충

트랙 검증 중 test 스테이징에서 계정 `0_tsuki2`(§3-3-1에도 등장 — 조회수 모수 2건 계정)의
`perf_summary`가 다음과 같이 나왔다.

> "…분석 기간 내 게시물들의 평균 좋아요 수는 **1,605개** 수준입니다."

`GeminiAccountSynthesizer.INSTRUCTIONS_TEMPLATE`의 perfSummary 절은 "**구체 수치를 문장에 그대로
인용하지 마라**"를 명시한다(§1에서 이미 언급한 그 지시 — 수치 정본은 화면 스탯 타일이고, 계정 카피는
`ELIGIBLE_WHERE`(§4) 재대상 전까지 며칠간 그대로 서빙되는 캐시라 낡은 값을 계속 노출하게 된다).
그런데 콘텐츠·계정 카피 프롬프트 조립부가 공유하던 `LlmGuard.RULES`(당시 유일한 상수)에 **"핵심
주장에는 근거 수치를 함께 인용하라"**는 상충 지시가 항상 붙어 있었다 — perfSummary 절 바로 뒤에
`%s`로 주입되는 절제 규칙 블록의 마지막 줄이 정면으로 반대되는 걸 요구한 것이다. LLM은 후자를
따랐다.

**정체**: 이 상충은 이번 트랙이 만든 게 아니라 `LlmGuard`가 계정 카피(GeminiAccountSynthesizer)와
콘텐츠 해석 문구(GeminiContentSynthesizer·AnthropicSynthesizer·GeminiContentAnalyzer 파트 B)에 같은
규칙 세트를 공유해 온 이래로 존재했던 구조적 결함이다. 콘텐츠 경로는 특정 게시물 1건의 확정된
사실(views·likes·comments)을 다루므로 수치가 낡지 않아 "근거 수치를 함께 인용하라"가 타당하지만,
계정 카피는 캐시·노후화 성질이 달라 이 지시가 애초부터 맞지 않았다. 지금까지 드러나지 않은 건
운 좋게 LLM이 규칙 순서·다른 지시와의 우선순위상 perfSummary 절을 따라준 표본이 많았을 뿐이다.

**결정 — 규칙을 전역에서 빼지 않고 계정 카피 경로에서만 스코프를 분리한다.** `LlmGuard`에
공통 3줄(표본 헤지·추론 금지·조언 금지)을 `COMMON`으로 묶고, 근거 수치 인용 지시는 `BODY`/`RULES`
(콘텐츠 경로 전용, 기존 값 그대로 유지)에만 남긴 뒤, `ACCOUNT_BODY`/`ACCOUNT_RULES`(계정 카피
전용, 인용 지시 제외)를 새로 추가했다. `GeminiAccountSynthesizer.instructions()`가 `LlmGuard.RULES`
대신 `LlmGuard.ACCOUNT_RULES`를 쓰도록 한 곳만 바꿨다 — `AnthropicAccountSynthesizer`는 이 메서드를
그대로 호출해 프롬프트를 만들므로(복제가 아니라 참조), 한 곳을 고치는 것으로 Gemini·Anthropic 양쪽
계정 카피 경로가 함께 바뀐다. 콘텐츠 경로 세 어댑터는 여전히 `LlmGuard.RULES`/`BODY`를 그대로 써서
동작이 바뀌지 않는다.

**§3-3-1 원칙("금지는 지시가 아니라 입력 제거로 강제한다")과의 관계**: 이 건은 같은 원칙을 그대로
적용할 수 없다 — `avg_likes`·`avg_comments` 등 수치는 perfSummary가 근거로 반드시 필요해 입력에서
뺄 수 없다(§3-3-0의 always-strip과 반대로, 이 수치들은 프롬프트에 남아 있어야 하는 값이다). 따라서
이 건은 지시 정리(상충 지시 제거)가 유일한 강제 수단이다. 대신 **생성 후 검증**을 관측 장치로
추가했다 — `AccountAnalysisWriter.hasNumericCitation()`이 저장 직전 `perf_summary`에 숫자가
있으면 WARN 로그를 남긴다(차단은 하지 않는다 — 계정 카피 배치는 실패 격리 원칙이라 이 검사로 정상
계정의 카피 저장을 막을 이유가 없고, 지시 정리 자체가 이미 §3-3-1의 실측처럼 "입력에서 제거한
것은 100% 지켜졌다"는 신뢰를 준다). 목적은 상충 지시가 다시 생기거나 이번 수정이 우회되는 회귀를
조기에 알아채는 것 — 곧 있을 운영 계정 7,033건 전량 재생성에서 특히 값어치가 있다.

관련 코드: [LlmGuard.java](../../../analytics/src/main/java/com/celfit/analytics/llm/LlmGuard.java),
[GeminiAccountSynthesizer.java](../../../analytics/src/main/java/com/celfit/analytics/llm/GeminiAccountSynthesizer.java),
[AccountAnalysisWriter.java](../../../analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisWriter.java).
