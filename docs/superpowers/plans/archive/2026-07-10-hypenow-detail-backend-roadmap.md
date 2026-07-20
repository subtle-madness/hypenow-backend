# hypenow 상세 분석 백엔드 — 구현 로드맵

> 상태: 🗄 대체됨 → ARCHITECTURE.md §4·§5
> ⚠️ **2026-07-12 설계 세션으로 대체됨.** 구조·태스크 분해의 기준은
> [`../../specs/2026-07-12-detail-analysis-design.md`](../../specs/2026-07-12-detail-analysis-design.md)를 볼 것.
> 이 문서는 이력 참고용으로만 남긴다.

> 기준 문서: 상세 분석 확정안 Artifact (2026-07-10, https://claude.ai/code/artifact/696bd39e-0f53-4bc5-916e-d4adbf013658)
> 제품 배경: `docs/superpowers/specs/2026-07-09-influencer-analysis-decisions.md`

확정안(게시물 드로어 v3 + 인플루언서 상세 v4)을 백엔드에서 구현하기 위한 전체 그림.
독립적으로 배포 가능한 5개 플랜으로 분해하며, 각 플랜은 별도 상세 계획 문서로 작성한다.

## 아키텍처 결정

**기존 데이터 흐름을 그대로 유지한다.**

```
crawler DB (원본 + LLM 분석 결과)
  → analytics.* 뷰 (crawler DB 내 읽기 전용 뷰)
  → MaterializationService 미러 (analysis DB 평탄 테이블)
  → was (JdbcTemplate/JdbcClient 조회, REST API)
```

- **LLM 파이프라인은 crawler 모듈 내 신규 bounded context `enrichment`로 구현한다.**
  crawler가 crawler DB의 유일한 쓰기 주체(Flyway 마이그레이션 소유)이고, 스케줄러·app_setting·
  DDD 패키지 구조가 이미 있으므로 aggregate 다음 파이프라인 단계로 자연스럽게 붙는다.
  분석 결과는 crawler DB의 신규 테이블(Flyway)에 저장하고, analytics 뷰가 집계해 was로 노출한다.
- **was는 읽기 전용을 유지한다.** 신규 API는 analysis DB의 미러 테이블만 조회한다.
- **"최근 N개 윈도우"는 `app_setting` 키 `analytics.recent-window`(기본 12)로 제어한다.**
  뷰가 직접 이 설정을 읽으므로 재배포 없이 조정 가능 (기존 SettingsService 패턴과 동일한 저장소).

## LLM 스택 결정

- **Anthropic Java SDK** (`com.anthropic:anthropic-java:2.48.0`) — crawler 모듈에 의존성 추가.
  ⚠️ SDK는 Jackson 2(`com.fasterxml`), Spring Boot 4는 Jackson 3(`tools.jackson`)을 쓴다.
  공존 검증을 Plan 3 Task 1의 컴파일 게이트로 강제한다.
- **모델: `claude-opus-4-8` 기본, 프로퍼티(`crawler.enrichment.model`)로 교체 가능.**
  분류(스팸·감성·구매의도·광고 유형)는 structured outputs로 스키마를 강제. 생성(헤드라인·브리핑·페르소나)도 동일 모델.
- **호출 경로: 동기 per-post `messages().create()`** — 게시물 1건 = 트랜잭션 1개로 부분 성공을 보존.
  Message Batches API(50% 할인)는 Plan 3 Task 0 결과를 본 뒤 후속 최적화로 유보.
- 💰 **비용(Plan 3에서 실측 산정, 게시물 1,000건 기준):** Opus 동기·무캐시·무배치 = **약 $61**,
  haiku 다운그레이드 시 **약 $12.2**. Batches 도입 후에야 $6.1대 도달. **사용자 결정 사항** —
  Plan 3 Task 0(정확도 스파이크) 결과를 보고 팀이 모델을 정한다. 코드 기본값이 Opus임에 주의.
- **안전장치:** `enrichment.batch-limit`(>500 거부) · `max-comments-per-call`(기본 50) · `dry-run`.
- API 키는 crawler 환경변수 `ANTHROPIC_API_KEY`로 주입.
  ⚠️ 이 리포에는 `.env`를 JVM env로 자동 로드하는 장치가 없다 — `APIFY_TOKEN`과 똑같이 셸 export가 필요.

## 플랜 분해와 의존성

```
Plan 1 (드로어 비LLM)
  ├─→ Plan 2 (인플루언서 비LLM)          ┐
  │     ↑ v_recent_content · was 공통설정 │─→ 프론트 개발 착수 가능
  └─→ Plan 3 Task 9 (was 응답 확장)      ┘
Plan 3 Task 0~8 (스파이크·crawler·analytics — Plan 1과 독립)
Plan 3 ──→ Plan 4 (LLM: 감지+왜 잘됐나) ──→ 드로어 완성
      └──→ Plan 5 (LLM: 인플루언서 생성 분석) ──→ 인플루언서 상세 완성
```

**⚠️ 최초 로드맵의 "Plan 1·2·3 완전 독립"은 상세 설계에서 수정됐다:**
- **Plan 2는 Plan 1에 의존한다.** Plan 1이 만든 `analytics.v_recent_content`(최근 N개 윈도우)와
  was의 `ClockConfig`/`WebConfig`/`IntegrationTest`를 재사용한다(윈도우 뷰 중복 생성 금지 원칙).
- **Plan 3의 Task 0~8은 Plan 1과 독립**이고, was 응답을 건드리는 Task 9만 Plan 1을 전제한다.
  따라서 **Plan 3 Task 0(스파이크)은 지금 당장 병렬로 돌릴 수 있다.**

**LLM 파이프라인이 최대 리스크**이므로 Plan 1 착수와 동시에 Plan 3 Task 0(실 댓글 골드셋 분류
정확도 + 실측 비용, opus vs haiku)을 병행하는 것을 권장한다.

**analytics 뷰 파일 번호 선점:** `09_post_detail.sql`(Plan 1) · `10_creator_detail.sql`(Plan 2)
· `11_comment_analysis.sql`(Plan 3). Plan 4·5는 12번부터.
`MaterializationService.VIEW_MAPPINGS`는 Plan 1·2·3이 모두 수정하므로 병합 충돌 지점이다.

### Plan 1 — 게시물 드로어 API (비LLM) ✅ 상세 계획 작성됨

`2026-07-10-plan1-post-drawer-api.md`

- 신규 뷰 `09_post_detail.sql`: 최근 N개 윈도우, 작성자 요약(히트율·평균 ER·평균 조회수),
  게시물 상세(미리보기+성과+벤치마크 3종+확산 배율+수집 댓글 수)
- 미러 등록 `post_detail` + was `GET /api/posts/{shortCode}` (헤더·미리보기·성과·댓글 통계 블록)
- LLM 블록(감지·왜 잘됐나·댓글 반응 분석)은 이 응답에 아직 없음 — Plan 3·4에서 필드 추가(additive)

### Plan 2 — 인플루언서 상세 API (비LLM) ✅ 상세 계획 작성됨

`2026-07-10-plan2-influencer-detail-api.md` — 7 Task / 37 스텝. **SQL은 실 crawler DB에 실행 검증 완료.**

- 신규 뷰 `10_creator_detail.sql`: 계정 1행 요약(`v_creator_detail`) + 1:N 자식 뷰 2종
  (`v_creator_view_series` 게시물별 조회수 시계열, `v_creator_ad_history` 협업 이력 골격)
- 미등록 08 기둥 뷰 4종 + 신규 3종을 `MaterializationService.VIEW_MAPPINGS`에 등록
- was `GET /api/influencers/{username}` — header/identity/performance/consistency/commercial 블록 구조
- **Plan 1의 `v_recent_content`·`v_author_summary`를 재사용** (윈도우 뷰 중복 생성 금지)
- 상세 설계에서 확정된 것:
  - **팔로워 구간 라벨은 tier(micro/mid/macro) 파생이 아니라 세분 `follower_band`** — 목업 "팔로워 1만~3만" 재현
  - **구간 내 포지션 = (세분 밴드 × 주력 카테고리) 내 ER 백분위** — 목업 "상위 8% (1만~3만 · 스킨케어)" 재현
  - 표본 4개 미만이면 변동성·모멘텀은 NULL(프론트가 "표본 부족" 처리)
  - 변동성 CV 경계(0.5/1.0)·모멘텀 하락 임계(15%)·표본 가드(4)는 모두 `app_setting` 키

### Plan 3 — LLM 파이프라인 1: 댓글 분석 ✅ 상세 계획 작성됨

`2026-07-10-plan3-llm-comment-analysis.md` — 11 Task / 80 스텝. **Task 0은 코드 착수 전 필수 스파이크.**

- crawler에 `enrichment` bounded context + Anthropic Java SDK 도입
- Flyway V7 `comment_analysis(comment_id PK, content_id, category, purchase_intent,
  keywords, model, prompt_version, analyzed_at)` — IRRELEVANT가 스팸·광고·무관 통합
- 파이프라인: **정규식 프리필터(명백한 스팸)** → structured-output 배치 분류 → 저장.
  게시물 1건 = 트랜잭션 1개(부분 성공 보존), `(comment_id, model, prompt_version)` 멱등
- 집계 뷰 `11_comment_analysis.sql` → 미러 → was 드로어 응답에 `commentAnalysis` 블록 additive
- 로드맵 초안에서 **변경된 결정**:
  - `keywords`는 `text[]`가 아니라 **`jsonb`** — 리포에 `text[]`↔Java 매핑 사례가 없고,
    `RawComment.payload`의 jsonb 매핑 관용구가 이미 확립돼 있음
  - **`prompt_version` 컬럼 추가** — 프롬프트·키워드 사전을 바꾸면 재분석이 트리거되도록.
    (단 PK가 `comment_id` 단독이라 과거 산출은 덮어써짐 — A/B·롤백은 후속 과제)
  - **Message Batches 유보** — 동기 per-post 호출로 시작. 비용 함의는 위 "LLM 스택 결정" 참조
- 감성·구매의도 비율의 **분모는 전체(스팸 포함)** 이고, 분자·분모 원값을 모두 응답에 포함
- ⚠️ **목업의 "214개 분석"은 도달 불가**: `aggregate.comments-per-post=50` ×
  `enrichment.max-comments-per-call=50` 이중 상한 때문에 게시물당 분석 댓글은 최대 50개다.
  상한 유지가 확정안이므로 **드로어 카피를 "최근 최대 50개 댓글 분석"으로 정정**해야 한다(프론트 반영 필요).

### Plan 4 — LLM 파이프라인 2: 감지 + 콘텐츠 속성 + "왜 잘됐나"

> 상세 계획은 **Plan 3 Task 0(스파이크) 결과 확정 후** 작성한다 — 모델 선택·프롬프트 구조·비용 경로가
> 이 플랜의 설계 전제이기 때문. 뷰 파일은 `12_` 번호부터.

- Flyway V8: `content_detection(content_id, kind[BRAND|PRODUCT|RETAILER], name, quote 인용 문장)`,
  `content_insight(content_id, headline, factors jsonb[요인·근거 쌍], attributes jsonb, generated_at)`
  (`content_attribute`를 별도 테이블로 둘지 `content_insight.attributes` jsonb로 합칠지는 이때 확정)
- 콘텐츠 속성 MVP 범위(미결사항 반영): **캡션·메타데이터 기반 필드만** — 훅 유형·제품 노출(영상 분석)은 백로그
- "왜 잘됐나" 생성 프롬프트에 실제 집계 수치(댓글 키워드 비율, 구매의도 수, 벤치마크)를 입력으로 제공
- was 드로어 응답에 `detection`·`attributes`·`whyItWorked` 블록 추가 → **드로어 v3 완성**

### Plan 5 — LLM 파이프라인 3: 인플루언서 생성 분석

> Plan 3·4 이후 작성. 뷰 파일은 `13_` 번호부터.

- Flyway V9: `account_insight(username, persona, briefing jsonb[강점3·리스크2], price_band, concept_tags jsonb,
  generated_at)`, `content_ad_type(content_id, ad_type[PAID|GROUP_BUY|SPONSORED|UNKNOWN])`
  (Plan 3의 결정에 따라 배열은 `text[]` 대신 `jsonb`)
- 광고 유형 분류(캡션 LLM), 분류 불가 건은 태그 없음(UNKNOWN) — 확정안 표기 원칙
- AI 협업 브리핑은 Plan 2 집계 수치를 프롬프트 입력으로 (모든 문장이 실수치 근거)
- 다뤄온 브랜드·유통사 칩·가격대 포지션은 Plan 4 감지 결과의 계정 단위 집계
- was 인플루언서 응답 완성 → **인플루언서 상세 v4 완성**

## 미결사항 6개 — 계획상 기본값

| 미결사항 | 계획에 반영한 기본값 | 바뀌면 영향 |
|---|---|---|
| 감성 비율 분모 | 전체 기준(스팸 포함), 원값을 응답에 포함해 프론트에서 전환 가능 | Plan 3 응답 필드 그대로, 프론트 계산만 변경 |
| 콘텐츠 속성 MVP 범위 | 캡션·메타 기반 필드만 (훅 유형·제품 노출 제외) | Plan 4 스코프 확대 시 영상 분석 파이프라인 별도 플랜 |
| 댓글 수집 상한 | 현행 50 (`aggregate.comments-per-post`) 유지 → **드로어 카피는 "최대 50개 분석"** (목업의 214 불가) | 상향 시 비용 재산정(Plan 3 판단 3) + 프론트 카피 |
| 벤치마크 기준선 | 작성자 평균 = 최근 N개(기본 12) 평균 조회수, 구간 평균 = 팔로워 tier 콘텐츠 평균, 카테고리 평균 = main_group 콘텐츠 평균 | Plan 1 뷰 SQL 수정 |
| 표본 기준 노출 수위 | 백엔드는 `sampleSize` 필드 항상 제공 — 노출 여부는 프론트 결정 | 없음 |
| 드로어 재고 제안(대표 댓글 펼침·이전/다음) | 미포함 (백로그) | 대표 댓글은 Plan 3 `comment_analysis`에서 추가 조회로 대응 가능 |

## 별도 결정 필요 (팀)

1. **후보 저장·상태 관리(검토중/컨택 예정/협업 중)·메모** — was 최초의 쓰기 경로가 필요
   (analysis DB에 앱 테이블 추가 또는 프론트 로컬 저장). MVP 범위인지부터 결정.
2. **LLM 분류 모델 다운그레이드 여부** — Plan 3 Task 0 스파이크의 정확도·비용 결과 확인 후.
   기본값 Opus = $61/1k, haiku = $12.2/1k (게시물 1,000건).
3. **드로어 "214개 분석" 카피 정정** — 실제 상한은 게시물당 50개. 프론트 카피 변경 필요.
4. **"구간 내 포지션"의 기준 지표** — Plan 2는 (세분 밴드 × 주력 카테고리) 내 **ER 백분위**로 확정.
   조회수/도달효율 기준을 원하면 뷰 `ORDER BY` 한 줄 변경 + 기대값 재산출.
3. **인플루언서 상세의 검색("우리 브랜드·경쟁사 협업 이력 검색")** — Plan 4 감지 결과에 대한
   단순 LIKE 검색으로 시작 가능. Plan 5에 포함할지 결정.
