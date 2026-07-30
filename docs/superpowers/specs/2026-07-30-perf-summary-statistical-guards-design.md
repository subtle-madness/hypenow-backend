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
| > 365 | `TOO_LONG` | 성장세 문장 **생략** | 11.7% |
| 90–365 | `LONG_SPAN` | "장기간에 걸친 변화"로 연화 | 22.9% |
| ≤ 90 | `OK` | 그대로 | 65.3% |

**포맷 비교 가능성**: `reels_count < 3` 또는 `feed_count < 3` → 포맷별 반응 차이 언급 금지.

### 3-3. 문구 층 — 프롬프트 주입

`INSTRUCTIONS_TEMPLATE`에 계산된 신뢰도 지침 블록을 주입하고, **수준 판정의 근거를
`avg_*` → `median_*`으로 교체**한다.

**부작용 차단**: `summary`는 `SELECT * FROM account_summaries`로 통째로 프롬프트에 들어간다
([AccountAnalysisJob.java](../../../analytics/src/main/java/com/celfit/analytics/analyze/AccountAnalysisJob.java)).
새 컬럼을 그대로 두면 `views_sample_count=1` 같은 **내부 수치가 LLM에 노출되어 문구에 인용될
여지**가 생긴다. 판정에 쓴 뒤 프롬프트 입력 맵에서는 제거한다.

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
- **Java 단위 테스트**: `PerfConfidence` 등급 경계값(2/3/5/6, 74/75, 90/365), 미러
  필드명↔컬럼명 매핑, 프롬프트 입력 맵에서 내부 컬럼이 제거되는지.
- **재생성 게이트**: `copy_version < VERSION`인 행이 후보로 잡히고 생성 후 현재 버전으로
  기록되는지.

## 7. 배포 순서 — 뷰 선적용 → 미러 → 분석 잡 (필수)

§3-1의 데이터 층(뷰 컬럼 9개 추가)은 Flyway가 아니라 [운영 런북](../../../analytics/README.md)에
따른 **수동 적용**이다. 반면 미러 테이블 컬럼(V44)은 Flyway로 자동 배포된다. 이 둘의 배포 경로가
다르다는 사실 자체가 순서를 어기면 실패하는 근본 원인이다.

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

`PerfConfidence.dataIncomplete()`(§3-2 확장)가 9개 컬럼 전부 NULL/부재 상태를 감지한다.
`views_sample_count`·`likes_sample_count`·`comments_sample_count`·`reels_count`·`feed_count`는
뷰에서 `count(*) FILTER (...)`로 계산되는데, 이 집계 함수는 매치되는 행이 0건이어도 SQL NULL이
아니라 정수 0을 반환한다 — 분석 이력이 하나라도 있는 계정(`GROUP BY`가 성립하는 조건)이라면 이
5개 컬럼은 **정상 운영에서 절대 NULL이 될 수 없다**. 예를 들어 피드 전용 계정은
`views_sample_count=0`·`reels_count=0`·`feed_count=12`처럼 값이 채워지지 NULL이 되지 않는다.
따라서 9개 전부 NULL은 "표본이 진짜 없는 계정"에서는 발생 불가능하고, 미러가 이 컬럼들에 아무것도
쓰지 못한 배포 과도기(위 시나리오 3단계)에서만 성립한다.

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
