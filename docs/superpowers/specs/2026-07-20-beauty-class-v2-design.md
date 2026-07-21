# 뷰티 판정 v2 — 4분류(beauty_class) 설계

> 상태: 🟢 활성 · 설계 확정 (2026-07-20)

## 1. 배경과 목적

hypenow의 뷰티 판정 목적은 **뷰티 제품(스킨케어·메이크업·향수 등)을 시딩·협찬·광고할
인플루언서를 리스트업**하는 것이다(광고성 게시물뿐 아니라 오가닉 콘텐츠 크리에이터 포함).
부수적으로, 뷰티 인플루언서를 필요로 하는 **뷰티 제품 제작·판매 회사**도 컨택 대상으로 함께
리스트업한다.

현행 3분류(INFLUENCER/COMPANY/NOT_BEAUTY) 프롬프트는 "에스테틱", "살롱"을 뷰티로
명시하고 있어 **피부과·성형외과·에스테틱샵 같은 시술·서비스 업체가 뷰티 인플루언서/회사로
섞여 들어온다**. 이들은 제품 시딩 대상이 아니므로 분리해야 한다.

## 2. 확정 요구사항

- 시술·서비스 쪽은 **별도 세그먼트로 분리**한다(버리지 않고 구분 저장):
  - 업체: 피부과·성형외과·에스테틱샵·헤어샵/미용실·네일샵·왁싱·속눈썹·반영구 등
    "시술·서비스를 파는 곳" 전부
  - 개인: 헤어 디자이너·네일 아티스트·시술 후기 위주 계정 등 시술·서비스 중심 개인
- INFLUENCER는 **뷰티 제품 콘텐츠 중심 개인**만, COMPANY는 **제품 제작·판매 회사**만.
- 판정 LLM은 **Gemini를 쓰지 않고 Anthropic SDK(구독 토큰)** 를 쓴다.
- 기존 판정분은 **MANUAL 포함 전체 초기화 후 재판정**한다.

## 3. 분류 체계 (4분류)

| class | 의미 | 파생 boolean |
|---|---|---|
| `INFLUENCER` | 뷰티 제품(스킨케어·메이크업·향수 등) 콘텐츠 중심 개인 크리에이터 — 시딩·협찬 타깃 | beauty=true, company=false |
| `COMPANY` | 뷰티 제품 제작·판매 회사(브랜드·쇼핑몰) — 컨택 타깃 | beauty=true, company=true |
| `BEAUTY_SERVICE` | 뷰티 영역이지만 시술·서비스 중심 — 병원(피부과·성형외과)·에스테틱·헤어샵·네일샵 등 업체와, 그 영역 개인(헤어 디자이너·네일 아티스트·시술 후기 위주) | **beauty=false**, company=false |
| `NOT_BEAUTY` | 뷰티 콘텐츠 중심이 아닌 계정 | beauty=false, company=false |

핵심 설계: **BEAUTY_SERVICE는 beauty=false로 파생**된다. 기존 다운스트림(SIMILAR 시드,
수집 대상 선정, 분석 뷰 서빙 모수 `beauty ∧ ¬beauty_company`, was)은 전부 boolean을 읽으므로
**무변경으로 자동 제외**된다. 세그먼트 구분이 필요한 곳(어드민 UI)만 `beauty_class`를 읽는다.

경계 규칙: 시술·서비스 업체가 자체 제품(화장품 라인)도 파는 경우 **주력이 무엇인지**로
판단한다 — 콘텐츠가 시술·매장 중심이면 BEAUTY_SERVICE, 제품 판매 중심이면 COMPANY.

## 4. 변경 사항

### 4-1. 스키마 (Flyway V16)

- `influencer.beauty_class` text 컬럼 추가 (NULL=미판정). CHECK 제약으로 4개 값 한정.
- 기존 `beauty`/`beauty_company` boolean은 유지 — 판정 시 파생값으로 함께 채운다.
- 기존 판정분 백필은 하지 않는다 — 전체 재판정(§4-5)으로 채워진다.

### 4-2. 프롬프트·파서 (ClaudeCliBeautyJudge — 단일 원천)

- 프롬프트 전면 개정: 판정 목적("뷰티 제품 시딩·협찬 대상 발굴")을 명시하고 4분류 정의·경계
  규칙(§3)을 담는다. 캡션 근거 지시는 유지.
- `parse()`는 4분류를 `Verdict(username, class, reason)`로 매핑 — Verdict record에
  beauty/company 파생 로직 포함(파생 규칙 단일 원천).
- 분류 외 값(모델 일탈)은 기존과 동일하게 스킵(미판정 유지 → 다음 실행 재시도).

### 4-3. 판정 구현 전환

- `crawler.beauty.judge` 기본값 `gemini` → `claude-api` (모델 `claude-haiku-4-5`).
- claude-api는 ClaudeClientFactory 경유 — `ANTHROPIC_AUTH_TOKEN`(구독 OAuth,
  `claude setup-token` 발급) 우선. **서버 환경변수에 authToken 설정 확인이 배포 조건.**
- GeminiBeautyJudge는 삭제하지 않고 남긴다(설정 롤백 경로) — 단 responseSchema를
  4분류에 맞게 갱신한다.

### 4-4. 저장·어드민

- BeautyJob: Verdict의 class를 `beauty_class`에 저장하고 boolean 파생값도 함께 저장.
  Summary·로그에 BEAUTY_SERVICE 구분 반영.
- 명단 UI(influencers.html): beauty_class 기준 4분류 배지 표시, 수동 오버라이드 버튼
  4분류로 확장(InfluencerBeautyController — class 파라미터로 전환).
- 대시보드 집계(StatusService)에 BEAUTY_SERVICE 카운트 추가.

### 4-5. 전체 재판정 (일회성 운영 작업)

- 초기화 SQL: `beauty_source` 구분 없이(MANUAL 포함) 판정분 전체를
  `beauty=NULL, beauty_company=NULL, beauty_class=NULL, beauty_source=NULL,
  beauty_reason=NULL, beauty_judged_at=NULL`로 리셋.
- 이후 기존 뷰티 잡이 새 기준으로 재판정 — 크롤링 주체는 오라클 서버이므로 **서버 어드민에서
  트리거**한다(로컬 크롤 실행 금지). 배치 한도(`beauty.batch-limit`)는 어드민 설정으로 조절.

## 5. 변경하지 않는 것

- SIMILAR 시드·수집 대상 리포지토리 쿼리, 분석 뷰(00_base/02_serving/20_landing_stats), was —
  boolean 파생 규칙 덕에 무변경.
- rejudge 선정 로직(beauty=false + 재료 갱신분) — BEAUTY_SERVICE도 beauty=false라
  재료가 갱신되면 자연히 재판정 대상이 된다(의도된 동작).
- GeminiBeautyJudge·ClaudeCliBeautyJudge 구현체 자체(전송 계층)는 유지 — 롤백 경로.

## 6. 테스트

- parse 4분류 매핑·파생 boolean 단위 테스트 (기존 ClaudeCliBeautyJudgeTest 확장).
- BeautyJob 저장 로직: beauty_class + 파생 boolean 저장 검증 (기존 BeautyJobTest 확장).
- 수동 오버라이드 4분류 컨트롤러 테스트 (InfluencerBeautyControllerTest 확장).
- 프롬프트 실판정 품질은 스모크(ClaudeApiBeautyJudgeSmokeTest 관용구)로 확인.
