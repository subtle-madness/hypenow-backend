# 태스크 C2 — 인플루언서 계정 LLM 카피 설계

> 상태: 🟢 활성 — C2 구현·E(인플루언서 API)의 카피 데이터 계약 기준
2026-07-14 (설계 세션 기록)

- 구조 기준: [ARCHITECTURE.md](../../../ARCHITECTURE.md) §4 · 데이터 계약: [C1 설계](2026-07-13-c1-account-detail-design.md)
- 기준 계약: celfit-front `AccountReport`의 LLM 카피 필드 (`report-types.ts`)
- 재사용 기반: B2·B3의 LLM 골격(Anthropic structured output 포트/어댑터, `AnalyticsSettings`,
  게이트 프로퍼티, 배치 상한, 건 단위 실패 격리 — `ContentAnalysisJob`·`AnthropicSynthesizer` 패턴)

## 1. 무엇을 만드나

계정마다 LLM 1콜로 인플루언서 패널의 **카피 7종**을 생성해 analysis DB에 저장하는 배치.
E는 숫자(C1 미러)와 카피(본 테이블)를 합쳐 서빙한다.

| 필드 | 화면 위치 (AccountReport) |
|---|---|
| `tagline` | 프로필 헤더 개인화 한 줄 |
| `summary` | AI 분석 요약 문단 |
| `trend_note` | 흐름 박스 문구 (`trend.note`) |
| `chart_note` | 차트 하단 캡션 (`chart.note`) |
| `traits` | 콘텐츠 성향 태그 칩 3~5개 (`contentMix.traits`) |
| `ad_headline` | 광고 비교 헤드라인 (`ads.headline`) — **비교 데이터 있을 때만, 없으면 NULL** |
| `pace_note` | 활동 페이스 문구 (`activity.paceNote`) |

**범위 밖**: 게시물 캡션 분류(카테고리·브랜드 감지·광고 여부 LLM 보강) — 크롤링 구조 개편(2026-07-14
확인: 분류는 caption 감지로 가는 방향)과 함께 **별도 후속 태스크**. `ads.brands` 칩은 그때까지 빈 배열.
서빙 API는 E(다른 세션).

## 2. 핵심 결정

1. **별도 잡 `AccountAnalysisJob`** — `ContentAnalysisJob`과 나란한 독립 배치. 멱등 규칙이 다르다
   (콘텐츠=불변 1회 vs 계정=stale 재분석)는 이유로 클래스를 분리한다.
2. **재분석 = stale 감지 + 쿨다운, 저장은 이력 INSERT.**
   - 대상: ① 분석이 아예 없는 계정은 **즉시** 대상. ② 분석이 있는 계정은
     `input_last_posted_at ≠ account_summaries.last_posted_at`(새 게시물 유입, stale)이고
     **마지막 분석 후 `analytics.account-analyze-cooldown-days`(기본 7일) 경과**한 경우만 대상.
   - 쿨다운 근거: 크롤링 구조 개편(매일 새 게시물 크롤)으로 stale이 활성 계정에서 거의 매일 발동
     — 쿨다운 없으면 계정 수 × 매일 LLM 콜. 배치 상한과 이중 가드.
   - 행은 INSERT로만 쌓고(분석 산출물 불변 컨벤션) E는 계정별 최신 1행을 읽는다.
3. **계약 record `AccountAnalysis`를 contract-analysis에 신설.** 생산자(잡)가 이 record로 조립해
   INSERT하고 소비자(was/E)가 같은 record로 SELECT — §4-4 "생산자+소비자 쌍" 성립.
   (※ `content_analyses`는 생산자가 행을 단일 타입으로 들지 않아 계약 없이 감 — 승격 여부는 별도 논의.)
4. **adHeadline 조건부**: `account_summaries`의 `organic_avg`·`ad_avg`가 둘 다 있을 때만 생성
   요청·저장, 아니면 NULL (프론트 계약 — comparison 없으면 headline null).
5. **설정·게이트** — 모델은 기존 `analytics.llm-model`(기본 opus) 재사용. 신규 `app_setting` 키:
   `analytics.account-analyze-batch-limit`(기본 10), `analytics.account-analyze-cooldown-days`(기본 7).
   배선 게이트는 신규 프로퍼티 `analytics.account-analyze-on-startup=true` (콘텐츠 분석과 독립 실행).

## 3. 저장 — `account_analyses` (analysis DB, Flyway V11, 분석 층 소유·Java 직접 쓰기)

```
id                     bigserial PK
handle                 text NOT NULL
analyzed_at            timestamptz NOT NULL
model                  text
input_last_posted_at   timestamptz   -- stale 판정 기준 (분석 당시 미러의 last_posted_at)
input_analyzed_count   bigint        -- 참고용 입력 스냅샷
tagline / summary / trend_note / chart_note / pace_note   text
traits                 jsonb         -- 문자열 배열 3~5개
ad_headline            text          -- nullable (§2-4)
```

인덱스 `(handle, analyzed_at DESC)`. 미러 테이블과 FK 없음(논리 참조).
계약 record: `AccountAnalysis(handle, analyzedAt, model, inputLastPostedAt, inputAnalyzedCount,
tagline, summary, trendNote, chartNote, traits, adHeadline, paceNote)` — traits는 `List<String>`
(jsonb 직렬화는 잡/소비자 각자의 매핑 계층에서, record는 순수 JDK 유지).

## 4. 잡 흐름 (`AccountAnalysisJob`, analytics `analyze` 패키지)

1. 대상 선별(§2-2) → `account-analyze-batch-limit` 상한 적용, handle 순 정렬.
2. 계정당 프롬프트 입력 (전부 C1 산출물 + 미러):
   `account_summaries` 1행(26지표) + `account_category_stats` + `account_content_series`(올린 순)
   + 해당 게시물 캡션(`contents.caption`, 게시물당 앞 300자 절단).
3. `AccountSynthesisPort.synthesize(...)` → structured output record `AccountCopy`(7필드).
   프롬프트 원칙은 기존 Synthesizer와 동일 — "주어진 수치만 근거, 수치를 지어내지 마라", 한국어,
   항목별 분량 지시. adHeadline은 비교 데이터가 있을 때만 지시에 포함.
4. 가드: `tagline`·`summary`가 비면 저장 전 실패(계정 skip → 다음 실행 재대상, B3의 빈 종합 가드와
   동일). `traits`는 5개 초과 시 앞 5개 절단, 0개면 실패.
5. `AccountAnalysis` record 조립 → INSERT. 계정 단위 try/catch 실패 격리, 완료 로그(처리/실패 수).

어댑터 `AnthropicAccountSynthesizer`(llm 패키지) — `AnthropicSynthesizer`와 동일 관용구
(structured output, 모델은 settings에서).

## 5. 검증

- **Testcontainers + 포트 fake** (실 API 호출 금지 — §4-7):
  대상 선별(신규/새 게시물 stale/쿨다운 미경과 제외/최신 제외), 이력 INSERT 누적(같은 계정 2행),
  빈 카피 실패 격리(다른 계정은 계속), 배치 상한, adHeadline 조건부(비교 없으면 프롬프트 요청도 없음).
- 계약 정합: `account_analyses` DDL ↔ `AccountAnalysis` record는 기존 `FlywaySchemaTest` 패턴으로
  대조 — 단 이 테이블은 미러가 아니라 컬럼 순서 강제가 아닌 **이름 집합 일치**만 확인해도 되지만,
  단순함을 위해 미러와 같은 순서 일치 규칙을 따른다.
- 실행 확인: 게이트 on + 상한 2~3으로 소량 실 실행 → `account_analyses` 행·카피 품질 확인.

## 6. 크롤링 구조 개편(2026-07-14 확인)이 남기는 후속 — C2 범위 밖, 기록용

| 항목 | 내용 |
|---|---|
| 윈도우 기간 전환 | 최근 3개월 크롤 확정 → `v_recent_content`를 개수(12)에서 기간 기반으로 전환 (B3 `recent12_*` 네이밍·프론트 12개 표기 동반 수정) |
| B3 숙성 가드 | 매일 크롤 시 게시 직후 분석·영구 고정 방지 — 대상 조건에 "게시 후 N일 경과" 추가 (재분석 불요) |
| 캡션 분류 태스크 | 카테고리·브랜드·광고 여부를 caption 감지로 — `ads.brands` 칩·main_group 결측 대응 포함 |

완료 시 ARCHITECTURE.md: §5 C2 ✅ + 위 3건을 §8 미결에 추가, §7 결정 기록(쿨다운·계약 record) 1줄.

## 7. 작업 방식

워크트리 `.worktrees/c2`, 브랜치 `feat/task-c2-account-llm`(origin/develop 기준), develop 대상 PR.
