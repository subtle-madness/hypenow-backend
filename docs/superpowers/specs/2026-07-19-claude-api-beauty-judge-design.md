# 뷰티 판정 Claude API(SDK) 어댑터 — 클라우드에서 구독 과금으로 판정

> 상태: 🟢 활성 · ✅ 구현/반영됨

## 배경

크롤러를 클라우드 서버로 옮기면(2026-07-19 배포 트랙) 뷰티 판정의 `claude-cli` 구현을
쓸 수 없다 — 로컬 맥의 Claude Code CLI(`claude -p`)를 자식 프로세스로 실행하는 구조라서다.
`BeautyJudge` 포트 주석에 예고돼 있던 "서버 배포 시 Anthropic API 구현으로 교체"를 실행한다.

사용자 요구의 핵심은 **비용 경로**: API 크레딧(토큰당 과금)이 아니라 **Claude 구독**으로
과금되어야 한다. 과금을 가르는 것은 SDK 여부가 아니라 자격증명이다:

| 자격증명 | 과금 |
|---|---|
| `ANTHROPIC_API_KEY` | API 크레딧 차감 |
| 구독 OAuth 토큰 + `anthropic-beta: oauth-2025-04-20` 헤더 | Claude 구독 포함 |

구독 토큰의 서버 발급 경로는 `ant auth login`(Console OAuth — API 과금이라 부적합)이 아니라
**`claude setup-token`**(Claude Code의 구독 OAuth, 헤드리스용 장기 토큰 `sk-ant-oat01-…`)이다.

## 결정

- `crawler.beauty.judge=claude-api`로 선택되는 세 번째 어댑터 **`ClaudeApiBeautyJudge`** 추가
  (기본은 gemini 유지 — 클라우드 프로파일/설정에서 claude-api 선택).
- 전송만 담당 — 프롬프트·파서는 기존 단일 원천(`ClaudeCliBeautyJudge.buildPrompt/parse`) 재사용
  (Gemini 어댑터와 같은 구조).
- 모델 `claude-haiku-4-5` (CLI가 쓰던 haiku와 동급, 50계정 배치 분류에 충분).
  `crawler.beauty.claude-model` 키로 교체 가능.
- 인증은 analytics `LlmClientFactory` 패턴을 crawler에 복제(모듈 간 import 금지 — §4-4):
  `ANTHROPIC_AUTH_TOKEN`(구독, oauth 베타 헤더) **우선**, `ANTHROPIC_API_KEY` 폴백,
  둘 다 없으면 판정 시점에 `ApifyException`(배치 실패 격리 — 앱 기동은 막지 않음).
  authToken이 있으면 apiKey를 SDK에 아예 넘기지 않아 이중 자격증명 거부·API 과금 유출을 차단.
- 실패 계약은 포트 그대로: 호출 오류·자격증명 부재·파싱 불가 → `ApifyException`,
  호출자(BeautyJob)가 배치 단위 격리. 재시도는 SDK 내장(429/5xx 2회)으로 충분 — 커스텀 재시도 없음.

## 서버 세팅 (운영 런북)

1. 로컬 맥에서 `claude setup-token` → 구독 장기 토큰 발급(터미널에 1회 표시).
2. 서버 환경변수 `ANTHROPIC_AUTH_TOKEN`에 주입(compose environment / systemd — `.env`는 JVM 자동 로드 안 됨).
3. 서버 어디에도 `ANTHROPIC_API_KEY`가 없는지 확인(있으면 SDK 기본 우선순위상 API 과금으로 샘).
4. 검증: 판정 1회 실행 후 Console usage에 API 사용량이 **안 찍히면** 구독 과금 확인.

## 비고

- 구독 토큰 호출은 구독 사용 한도(rate limit)를 소모한다 — Claude Code 사용과 한도 공유.
- 토큰이 만료·회수되면 `claude setup-token` 재발급 후 env 교체.
