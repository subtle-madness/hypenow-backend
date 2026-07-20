# Vertex AI 전환 + 최근 12개 백필 자격 재설계 — 설계

> 상태: 🟢 활성

2026-07-20. PO 결정: AI 분석 대상을 인플루언서 최근 12개 게시물까지 재확대(백필 재도입 —
07-19의 "백필 MVP 제외" 결정 번복), LLM 인프라는 물량 증가 대비 Vertex AI로 완전 전환.
$300 무료 체험 크레딧 활용이 동기 — AI Studio Gemini API에는 크레딧이 적용되지 않으나
(2026-03 정책 변경) Vertex AI의 구글 퍼블리셔 Gemini 모델에는 적용된다(제외 조항은
AI Studio·파트너 MaaS 모델뿐).

## 결정 요약

| 항목 | 결정 |
|---|---|
| 범위 | (A) Vertex 전환(동기+배치) → (B) 최근 12개 백필 자격 — 순차, 한 트랙 |
| 일상 경로 | Vertex로 **완전 전환** (하이브리드 기각 — 쿼터 이월·버스트 운영 부담 제거) |
| crawler | **무접촉** — GeminiBeautyJudge는 무료 AI Studio 키 유지(무료 티어는 크레딧과 무관하게 존속, 판정 물량은 한도 내) |
| 구현 방식 | 순수 REST + `google-auth-library-oauth2-http`만 추가 (SDK 도입 기각 — 검증된 REST 코드 재사용, 의존성 최소) |
| 인증 | 서비스 계정 OAuth (express mode API 키는 배치 미지원·3.1-flash-lite 미등재·크레딧 별개라 기각) |
| 엔드포인트 | global (`gemini-3.1-flash-lite`는 global/us/eu만 제공 — 도쿄 리전 없음) |
| GCP 계정 | 기존 구글 계정, $300 크레딧 **백필 준비 완료 시점에 활성화** (90일 카운트다운 최소화) |
| 롤백 | app_setting `analytics.llm-provider=gemini` 복원 + 재기동 |

## 조사 근거 (2026-07-20, 공식 문서)

- 동기: `POST https://aiplatform.googleapis.com/v1/projects/{P}/locations/global/publishers/google/models/{M}:generateContent`,
  `Authorization: Bearer <SA 토큰>`. 바디 스키마는 AI Studio와 동일 camelCase
  (`systemInstruction`/`generationConfig`/`responseSchema` 지원).
- 배치: `POST .../locations/global/batchPredictionJobs` — GCS JSONL 입력
  (`{"request": {...}}` 한 줄), 출력은 GCS prefix 밑 여러 파일, 라인에 `status`/`request`(에코)/`response`.
  **`key` 같은 상관관계 필드 없음, 출력 순서 보장 없음.** 실시간 대비 50% 할인,
  사전 정의 쿼터 없음(동적 공유 풀), 잡당 20만 건·입력 1GB·처리 24h 제한.
- 모델: `gemini-3.1-flash-lite` Vertex GA(2026-05-07), 모델 ID 동일.
- 쿼터: DSQ(Dynamic Shared Quota) — 고정 RPM/RPD 없음.
- 검증 유보(구현 중 스모크로 확정): batch `model` 필드의 짧은/풀 경로 어느 쪽이 정본인지,
  GCS 버킷 리전 제약, 429 실동작.

## A. Vertex 전환

### A-1. 인증·동기 경로

- **`VertexTokenProvider`** (신규, analytics): `GOOGLE_APPLICATION_CREDENTIALS` 경로의 SA JSON →
  `cloud-platform` 스코프 액세스 토큰. `google-auth-library-oauth2-http:1.49.0` 사용,
  만료 자동 갱신(`refreshIfExpired`), 스레드 안전.
- **`VertexHttpApi implements GeminiApi, GeminiBatchApi`** (신규): 순수 JDK HTTP.
  - 동기: 위 global 엔드포인트. 요청 바디는 기존 `GeminiHttpApi.requestBody()` **재사용**
    (static 유지 — 프롬프트·스키마·파서 무접촉).
  - **RPM 페이싱 제거** (DSQ). 429/5xx 지수 백오프는 유지. 재시도 소진 429 →
    기존 `LlmQuotaExhaustedException` 그대로 던져 잡의 이월 로직 무접촉
    (의미는 "일 한도"에서 "일시 용량 부족 이월"로 재해석 — 주석만 갱신).
  - usage 로깅 관용구(`gemini usage: model=… input=… output=…`) 유지 — 어드민 비용 카드 호환.
- **배선**: `analytics.llm-provider`에 `vertex` 값 추가. `LlmConfig`에서 provider가 `vertex`면
  `GeminiApi` 빈을 `VertexHttpApi`로 — `GeminiContentAnalyzer`·`GeminiAccountSynthesizer` 무접촉.
- **신규 설정** (app_setting): `analytics.vertex-project`, `analytics.vertex-location`(기본 `global`),
  `analytics.vertex-gcs-bucket`. 환경변수: `GOOGLE_APPLICATION_CREDENTIALS`(SA 키 경로, 셸 export —
  `.env` 자동 로드 안 됨 함정 동일).

### A-2. 배치 경로

- 흐름: JSONL 생성 → GCS 업로드(REST `POST /upload/storage/v1/b/{B}/o?uploadType=media`,
  입력 ~50MB라 simple upload 충분) → 잡 생성 → 폴링(`state=JOB_STATE_SUCCEEDED`) →
  출력 prefix 오브젝트 목록 조회 → 다운로드·파싱·저장.
- **상관관계 재설계**: 출력 라인에 에코되는 `request`의 사용자 텍스트에서 short_code를 복원.
  `GeminiContentAnalyzer.userText()` 첫 줄이 `콘텐츠: {shortCode} (@핸들, 타입)`이라 이미 포함됨 —
  에코된 첫 파트 텍스트의 첫 줄을 파싱한다(프롬프트 무변경). GCP `labels`는 값 소문자 제약이
  인스타 shortCode(대소문자 혼합)와 충돌해 기각. 사이드카(short_code→기준선 스냅샷)는 그대로 —
  복원한 short_code로 조회.
- **`GeminiBatchApi` 시그니처 조정**: 출력이 "파일 하나"가 아니라 "prefix 밑 여러 파일"인
  차이만 반영(예: `downloadResults(prefix)`가 목록 조회+병합). `GeminiHttpApi`(AI Studio 구현)와
  `GeminiBackfillRunner`는 이 계약에 맞춰 최소 수정 — 사이드카·멱등 INSERT(`ON CONFLICT DO NOTHING`)·
  `late_backfill` 마킹(V33) 재사용.
- GCS 버킷 1개(us 멀티리전 — 모델 가용 리전 정합), `input/`·`output/` prefix 분리.

### A-3. 사용자 런북 (계정·결제는 사용자 직접)

① $300 무료 체험 활성화 — **백필 실행 준비 완료 후** ② 프로젝트 생성, Vertex AI API·
Cloud Storage API 활성화 ③ 서비스 계정 생성(역할: Vertex AI User, Storage Object Admin),
JSON 키 발급 ④ 키를 오라클 서버 업로드 + `GOOGLE_APPLICATION_CREDENTIALS` 등록.
이후 스모크(동기 1콜 → 10건 배치 → 본 백필)는 에이전트가 진행.

## B. 최근 12개 백필 자격

현황: 백필 차단 지점은 정확히 두 곳 — 04 뷰의 제때 크롤 EXISTS 가드(백필 러너 입구)와
`ContentAnalysisJob`의 동일 취지 SQL 가드(일상 잡). 기준선(03)은 최근 N 윈도우
(`v_recent_content`) 기반이라 백필 게시물에도 이미 정의됨.

- **04 뷰**: 자격을 `제때 크롤 EXISTS **OR** 최근 N개 윈도우 포함(v_recent_content)`으로 확장,
  `timely` 불리언 컬럼 노출(마킹 판단용). 주석의 "백필 MVP 제외" 서사를 본 결정으로 갱신.
- **`ContentAnalysisJob`**: 자격 SQL에 같은 OR — 미러 `contents`에서 계정별
  `row_number() ≤ recent-window`(값은 raw `app_setting`을 Java에서 읽어 파라미터로 전달,
  `AnalyticsSettings`에 `recentWindow()` 추가). 마킹은 하드코딩 `"timely"` 대신 제때 여부에 따라
  `timely` / `late_backfill`(+ `metric_snapshot_late=true`).
- **실행 전략**: 초기 물량(~1.4만 = 1,500계정×12 − 기분석 ~4천)은 Vertex 배치 러너 일회로
  소화(50% 할인, 추정 $5~10 — 크레딧 내). 이후 신규 계정 유입분(계정당 최대 12건)은
  일상 잡이 배치 상한(450) 내에서 자연 흡수.
- **서빙 영향**: `late_backfill` 행은 기준선이 늦크롤 지표 기반 — 기존 V33 마킹 소비 규칙 그대로,
  서빙 뷰·was 무접촉.
- **마킹 정합 (구현 중 확정 — 최종 리뷰 반영)**: ①일상 잡의 윈도우 경로는 **제때창이 완전히
  닫힌 뒤에만** 자격을 준다(창 열림 중 조기 분석 → `late_backfill` 영구 고정 방지 — 04 뷰의
  "창 완전 경과" 철학과 정렬). ②04 뷰 timely(KST 캘린더일 판정)와 일상 잡 timely(미러 간격 근사
  판정)는 술어가 미세하게 달라 경계 콘텐츠는 어느 경로가 먼저 잡느냐에 따라 라벨이 갈릴 수 있다 —
  미러에 스냅샷 캐시가 없어 동일 판정 이식이 불가하고, 라벨은 코스한 품질 플래그(서빙 정책 미결)라
  **잔차는 수용**한다. ③백필 러너는 뷰의 timely 판정을 사이드카로 승계해 행 단위 분기 마킹.

## 테스트·검증

- 단위: `VertexHttpApi` 요청 바디·토큰 헤더(fake 토큰 프로바이더), 상관관계 복원(에코 파싱),
  수거 파싱·실패 라인 격리, `ContentAnalysisJob` 마킹 분기.
- SQL 하니스: `analytics/test/04_*.test.sql`에 "창 안 늦크롤 → 후보 포함·timely=false",
  "창 밖 늦크롤 → 제외" 케이스 추가.
- 실 스모크(GCP 준비 후): 동기 1콜 → 소형 배치(10건) → 본 백필. 배치 `model` 경로 형식·
  버킷 리전 제약은 이 단계에서 확정.

## 비용·물량 전망

- 백필 일회: ~1.4만 콜 × (in ~1.5k / out ~0.3k tok) ≈ in 21M·out 4.2M — 배치 50% 할인 시 $5~10.
- 일상: 일 ~700콜 → 월 수 달러(크레딧 소진 후). 계정 증가 시 DSQ라 한도 조정 불필요,
  비용은 콜 수 비례.
- $300 크레딧: 활성화 후 90일. 백필+수개월 일상+향후 비전 첨부 실험 여지까지 커버.
