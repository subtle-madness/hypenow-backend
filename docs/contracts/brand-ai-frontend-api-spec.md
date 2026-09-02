# 브랜드 AI 어시스턴트 API 스펙

> 상태: 🟢 활성 · 기준 브랜치 feat/brand-ai-tool-limits-redesign · 2026-09-02

hypenow 브랜드 모니터링 화면의 AI 어시스턴트(자유 질의 챗) 기능 백엔드 API 계약 문서. was 실제 구현 코드(`was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/`)를 직접 읽어 작성했다 - **코드가 정본이고 이 문서는 옮겨 적은 것**이다. 코드에서 눈으로 확인하지 못한 항목은 "확인 필요"로 명시했다.

- 대상 독자: 프론트엔드 개발자, was 구현 에이전트
- 공통 규약(응답 envelope 형태·에러 코드 표 형식·날짜 포맷 표기·enum 확장 규약 등)의 일반 원칙은 [monitoring-frontend-api-spec.md](monitoring-frontend-api-spec.md) §1을 그대로 재사용한다. 이 문서는 그 원칙이 브랜드 AI 표면에서 실제로 어떻게 구현돼 있는지만 다룬다.
- 이 문서는 **브랜드 AI 어시스턴트(질의·SSE·프리셋·대화·피드백·사용량)만** 다룬다. 툴 내부 구현(`BrandAiToolbox.java`)과 날조 방지 가드(`BrandAiGroundednessGuard.java`)는 이 세션이 편집 중인 대상이라 열지 않았고, 이 문서에도 다루지 않는다.
- 대상 코드: `V1BrandAiMessagesController`·`V1BrandAiConversationController`·`V1BrandAiFeedbackController`·`V1BrandAiUsageController`와 그 DTO들, `BrandAiPresets`·`BrandAiPrompt`·`BrandAiAgent`·`AiChatQuota`·`BrandAiFollowUpGenerator`·`BrandAiConfig`·`AiChatLogRepository`.

## 0. 이 문서 읽는 법

| 태그 | 의미 |
|---|---|
| `[확인 필요]` | 이 세션이 연 파일 범위에서 코드로 직접 확인하지 못했거나, 코드에 근거가 없어 단정할 수 없는 항목 |

태그 없는 내용은 전부 위 대상 코드에서 실제로 읽은 값·문자열·상수다.

## 1. 공통 규약

### 1.1 개요

| 항목 | 값 |
|---|---|
| Base URL | `https://api.hypenow.io` (monitoring과 동일) |
| 버저닝 | URL prefix `/v1` |
| 프로토콜 | HTTPS only |
| 요청·응답 본문 | JSON (UTF-8). SSE 변형만 `text/event-stream` |
| 인증 | 세션 쿠키. 다른 v1 엔드포인트와 동일 - [monitoring-frontend-api-spec.md §1.1](monitoring-frontend-api-spec.md) 참조. CSRF(`X-XSRF-TOKEN`) 관용구도 동일하다고 판단되나, 이 도메인 전용 CSRF 예외 설정 유무는 `SecurityConfig.java` 전체를 읽지 않아 **확인 필요** |
| 킬 스위치 | 4개 컨트롤러 전부 `@ConditionalOnProperty(name = {"monitoring.enabled", "monitoring.brand.ai.enabled"}, havingValue = "true")` - 둘 다 true여야 빈이 등록된다. 꺼져 있으면 경로 자체가 없어 404 |

### 1.2 응답 envelope

같은 공용 `ApiResponse<T>`(`com.celfit.was.v1.common.ApiResponse`)를 쓴다 - monitoring과 완전히 동일한 형태(`success`·`data`·`error`·`meta`). `meta`는 `@JsonInclude(NON_NULL)`이라 이 도메인 응답에서는 전부 생략된다(목록 응답도 `meta`를 쓰지 않는다, 아래 §6.1 참조).

DELETE 두 곳(`DELETE /ai/conversations/{id}`, `DELETE /ai/messages/{messageId}/feedback`)은 envelope 없이 HTTP 204(본문 없음) - monitoring 문서의 "204는 예외" 규칙과 동일.

### 1.3 날짜·시간 포맷

`OffsetDateTime` 필드(`AiConversationSummary.updatedAt`, `AiConversationMessage.createdAt`·`feedback.at`, `AiUsageResponse.resetAt` 등)의 실제 직렬화 오프셋(KST `+09:00`인지 UTC인지)은 전역 Jackson 설정 파일을 찾지 못해 **확인 필요** - 이 도메인 전용 `ObjectMapper` 커스터마이징 클래스는 없었고(`BrandAiConfig.java`에 `ObjectMapper` 빈 정의 없음), Spring Boot 자동 설정 `ObjectMapper`를 그대로 쓰는 것으로 보인다. `AiChatQuota.resetAt()`은 KST 자정 기준으로 계산은 하지만(§8), 직렬화 시 오프셋 표기까지는 이 세션에서 검증하지 못했다.

`AiScope`의 `dateFrom`/`dateTo`는 `LocalDate.parse()`로 파싱하므로 요청 쪽은 `YYYY-MM-DD` 고정(`AiScope.java:48`).

### 1.4 보호 장치 3중 (질의 엔드포인트 전용)

`V1BrandAiMessagesController` 클래스 javadoc(43~52행)에 명시된 대로 세 겹이며 전부 429로 수렴하되 세부가 다르다.

| 겹 | 구현 | 기준값 | app_setting 키 |
|---|---|---|---|
| 분당 버스트 | `RateLimiter.tryAcquire("ai-chat:" + userId, perMinuteLimit())` | 10회/분 | `ai.chat.per-minute-limit` |
| 일일 총량 | `AiChatQuota.requireWithinDailyLimit` | 30회/일(KST 자정 리셋) | `ai.chat.daily-limit` |
| 동시 실행 | 전용 풀 `brandAiChatExecutor`(`BrandAiConfig.java:159`, 큐 없이 2 스레드, `AbortPolicy`) | 초과 시 즉시 거절 | 없음(코드 상수) |

기준값은 마이그레이션이 시드한 `app_setting` 행이고, 조회 실패(값이 숫자가 아님 등) 시 코드 기본값(10/30)으로 폴백한다(`V1BrandAiMessagesController.perMinuteLimit()`, `AiChatQuota.dailyLimit()`).

## 2. 엔드포인트 목록

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/v1/brand-monitoring/ai/messages` | 질의(완결 JSON). `Accept: text/event-stream`이면 SSE 변형 |
| GET | `/v1/brand-monitoring/ai/conversations` | 대화 목록 |
| GET | `/v1/brand-monitoring/ai/conversations/{id}` | 대화 상세 |
| DELETE | `/v1/brand-monitoring/ai/conversations/{id}` | 대화 삭제(soft delete) |
| PUT | `/v1/brand-monitoring/ai/messages/{messageId}/feedback` | 피드백 저장(멱등) |
| DELETE | `/v1/brand-monitoring/ai/messages/{messageId}/feedback` | 피드백 취소 |
| GET | `/v1/brand-monitoring/ai/usage` | 오늘 사용량 조회 |

대화 **생성**을 위한 별도 엔드포인트는 없다 - `POST /ai/messages`가 `conversationId`를 안 받으면 실행 풀 수용이 확정된 시점에 새 대화를 만든다(§3, F2).

## 3. 질의 (완결 JSON) `POST /v1/brand-monitoring/ai/messages`

### 3.1 요청 (`AiMessagesRequest`)

| 필드 | 타입 | 필수 | 검증·의미 |
|---|---|---|---|
| `conversationId` | string \| null | 아니오 | null·미지정이면 신규 대화. 지정 시 숫자 파싱 실패는 400, 미소유·삭제된 대화는 404, 다른 브랜드 소속이면 409 `CONVERSATION_SCOPE_MISMATCH` |
| `accountIds` | string[] | 예 | **정확히 1개**여야 한다(`request.accountIds().size() != 1` → 400 "accountIds는 정확히 1개여야 해요."). 그 외는 컨트롤러 범위 밖(다중 계정 비교 미지원) |
| `presetId` | string \| null | 아니오 | §5 참조. 미등록 값도 오류 아님(자유 질의 폴백) |
| `text` | string | 예 | 1~2,000자. 빈 값/공백만이면 400, 2,000자 초과면 400 "메시지가 너무 길어요. 더 짧게 나눠서 물어봐 주세요." |
| `scope` | object \| null | 아니오 | 아래 §3.2. null이면 무필터 |

accountIds[0]은 `Long.parseLong` 실패 시 400, 파싱 성공해도 `BrandLinkRepository.findActiveByUserAndBrand`로 소유 확인 안 되면 404.

### 3.2 `scope` (`AiMessagesRequest.ScopeRequest` → `AiScope`)

| 필드 | 타입 | 정규화 규칙 |
|---|---|---|
| `dateFrom`, `dateTo` | string(`YYYY-MM-DD`) \| null | `LocalDate.parse` 실패 시 400 "scope.dateFrom가 올바른 날짜 형식이 아니에요(YYYY-MM-DD)." |
| `mediaType` | string \| null | `"all"`·공백은 null로 접힘, 그 외는 소문자로 정규화만(값 자체를 enum으로 검증하지는 않음). `summaryLine()`은 `reels`/`feed`만 특별 처리하고 그 외 값은 원문 그대로 노출 |
| `sponsorship` | string \| null | 위와 동일 정규화. `summaryLine()`이 인식하는 값은 `sponsored`/`organic`/`unknown` |
| `source` | string \| null | 위와 동일 정규화. `summaryLine()`이 인식하는 값은 `tagged`/`direct` |
| `followerMin`, `followerMax` | int \| null | 그대로 통과, 검증 없음 |
| `q` | string \| null | trim, 빈 값이면 null |

정규화된 `AiScope`는 시스템 프롬프트에 "[현재 화면 필터] ..." 한 줄로 요약돼 주입된다(`AiScope.summaryLine()`, `AiScope.java:107`). scope가 비어 있으면 이 줄 자체가 붙지 않는다.

### 3.3 응답 (`AiMessagesResponse`)

| 필드 | 타입 | 의미 |
|---|---|---|
| `conversationId` | string | 이 질문이 속한 대화 id(신규면 이번에 생성된 id) |
| `messageId` | string \| null | 로그 적재 행 id. 로그 적재는 fire-and-forget이라 실패 시 null(`AiChatLogRepository.insert`) |
| `content` | string | 답변 마크다운 본문(§9) |
| `followUps` | array | `{text, kind}`. **0~2개** - "정확히 2개"는 LLM에게 준 프롬프트 지시일 뿐, 서버가 최종적으로 강제하는 계약은 "최대 2개"다(`BrandAiFollowUpGenerator.MAX_FOLLOW_UPS=2`, kind가 `deepen`/`action`이 아니거나 text가 빈 항목은 버림). 생성 실패·타임아웃·예산 부족이면 빈 배열 |
| `references` | array | `{type, brandPostId, label}`. **최대 10개**(`BrandAiAgent.MAX_REFERENCES=10`). `type`은 현재 `"post"`만 존재(`AiMessagesResponse.Reference.TYPE_POST`). `brandPostId`는 shortCode 그대로. 답변 텍스트에 실제로 등장한(`answer.contains(shortCode)`) shortCode만 골라 담는다 - 툴이 조회했지만 답변에 언급 안 된 게시물은 안 실림 |
| `limitReached` | string \| null | `"budget"`(툴 호출 상한·프롬프트 토큰 예산 소진) 또는 `"time"`(85초 벽시계 예산 소진), 정상 완료면 null |

`followUp.kind`는 `"deepen"`(구체 대상 심화) 또는 `"action"`(다음 행동 유도)만 존재.

### 3.4 응답 시간 계약

- 컨트롤러 상수 `RESPONSE_TIMEOUT_SECONDS = 90`(초). `future.get(90, SECONDS)`로 강제.
- 에이전트 내부 벽시계 예산은 85초(`BrandAiAgent.TIME_BUDGET_MILLIS`), Vertex 요청 타임아웃은 45초 - 둘 다 90초보다 먼저 걸려 정상적으로는 90초 타임아웃까지 가지 않는 게 설계 의도(코드 주석, `awaitOutcome` javadoc).
- 90초를 넘기면: `future.cancel(true)` 호출(단, 실행 중 스레드를 인터럽트하지는 않음 - `CompletableFuture.cancel` 명세상 한계) 후 `TimeoutException`을 잡아 **502 `AI_UNAVAILABLE`**, 메시지 "답변 생성이 너무 오래 걸렸어요. 잠시 후 다시 시도해 주세요." 로그에는 `outcome=timeout`으로 남고 이 요청은 일일 상한 차감에 **포함**된다(§8 참조).
- LLM 전송 실패·인터럽트·쿼터 소진 등 그 외 실패도 코드는 동일하게 502 `AI_UNAVAILABLE`이지만 내부 `outcome=llm_failed`로 로그돼 일일 상한 차감에서 **제외**된다.

### 3.5 검증 순서

`resolveConversationRef`(대화 참조 400/404/409) → 분당 rate limiter(429) → 일일 상한(429). 즉 형식·소유권 오류는 분당·일일 버킷을 소모하지 않는다(F10, 컨트롤러 javadoc 50~52행).

## 4. SSE 스트리밍 (같은 경로, `Accept: text/event-stream`)

같은 `POST /v1/brand-monitoring/ai/messages`를 `produces` 협상으로 나눈 변형(`V1BrandAiMessagesController.messagesStream`). 사전 검증(로그인·요청 형식·대화 참조·rate limit·일일 상한)은 SSE를 열기 **전에** 끝내고, 실패하면 SSE 이벤트가 아니라 **JSON**을 직접 써서 응답한다(`writeJsonError` - 완결 경로와 같은 `ApiResponse.fail` envelope, `V1ExceptionAdvice`를 거치지 않고 서블릿 응답에 직접 write, UTF-8 명시).

### 4.1 이벤트 종류

| 이벤트 | 발생 시점 | payload |
|---|---|---|
| `meta` | SSE 시작 직후, 대화 id 확정 후 1회 | `{conversationId}` |
| `status` | 아래 §4.2 | `{stage, ...}` |
| `delta` | 답변 텍스트 도착 시(§4.3 참조 - 홀드백 여부에 따라 1회 또는 여러 번) | `{text}` |
| `done` | 정상 완료 | `{messageId, followUps, references, limitReached}` |
| `error` | LLM 처리 자체가 예외로 실패했을 때만 | `{code, message}` - 코드는 항상 `"AI_UNAVAILABLE"`(코드에 이 값을 만드는 지점이 한 곳뿐), 메시지 "답변을 받지 못했어요. 잠시 후 다시 시도해 주세요." |

`error` 이벤트는 `runSse` 안에서 `agent.run(...)`이 `RuntimeException`을 던졌을 때만 발생한다(`LlmQuotaExhaustedException`이면 warn, 그 외는 error 레벨 로그 후 동일 이벤트). 사전 검증 실패(400/404/409/429)는 위에서 설명한 대로 SSE `error` 이벤트가 아니라 JSON 응답이다 - **혼동 주의**.

유저 중단(`OUTCOME_ABORTED`)은 `error`도 `done`도 보내지 않고 로그만 남긴 뒤 `emitter.complete()`로 조용히 끝난다.

### 4.2 `status` 이벤트의 `stage`

`stage`는 세 가지뿐이다(컨트롤러 javadoc 277~287행, 2026-09-02 확장):

| stage | 발생 시점 | 추가 필드 |
|---|---|---|
| `thinking` | 매 LLM 호출 직전(날조 방지 재시도 턴 포함) | `index`(1부터, 이번 run() 전체에서 몇 번째 LLM 호출인지), `label: "생각하는 중"` |
| `tool` | 매 툴 실행 직전 | `tool`(툴 이름), `index`(1부터, 몇 번째 툴 호출인지), `label`(`BrandAiToolSpecs.labelFor(toolName)`이 정본) |
| `writing` | 홀드백 중인 일반 턴에서 텍스트 청크가 처음 도착한 순간, 그 턴에 1회만 | `label: "답변 정리하는 중"` |

이벤트 순서 예시(컨트롤러 javadoc 그대로):
```
meta
→ status{stage:thinking,index:1}
→ status{stage:tool,tool:list_posts,index:1}
→ status{stage:thinking,index:2}
→ status{stage:writing}
→ delta{text:...}
→ done
```
문서에 언급된 `meta → thinking → (tool → thinking)* → writing → delta → done` 패턴은 실제 코드 흐름과 일치한다(단 `writing`은 항상 나오는 게 아니라 §4.3의 홀드백 조건에 걸릴 때만 - 강제 답변 턴에서는 안 나온다).

### 4.3 본문 스트리밍 여부 (홀드백 불변식)

**턴 종류에 따라 다르다** - 완전한 토큰 단위 스트리밍이 아니다.

- **일반 턴**(툴 상한·시간 예산에 아직 안 걸린 정상 진행): 텍스트를 누적만 하다가 그 턴이 functionCall 없이 순수 텍스트로 끝난(=최종 답변 확정) 시점에 **`delta` 1회로 전체 답변을 한꺼번에** 보낸다(`listener.onAnswerDelta(answer)`, `BrandAiAgent.java:513`). 그 사이 텍스트가 도착하기 시작하면 `writing` status만 1회 통지된다.
- **강제 답변 턴**(`capped=true`, 툴 상한·토큰 예산·85초 벽시계 예산 도달 후 마지막 강제 답변 턴): 이때만 `chunk.textDelta()`가 도착하는 대로 즉시 `delta`로 흘려보낸다(`liveEmit=true`, `BrandAiAgent.java:461~470`) - 실제 토큰 단위 스트리밍은 이 경우에만 일어난다.

이 불변식이 있는 이유는 코드 주석에 명시돼 있다 - Gemini가 한 턴의 parts 배열에 텍스트+functionCall을 함께 담아 반환할 수 있어, 무조건 라이브 방출하면 결국 버려질 "고아 텍스트"가 화면에 남을 수 있기 때문(`BrandAiAgent.java:356~368`).

### 4.4 연결 중단 시 사용량 차감

클라이언트 연결 중단(탭 종료·새로고침 등)·SSE 자체 타임아웃(90초)·정상 완료 어느 경우든 `aborted` 플래그가 세워지고, 에이전트 루프는 다음 확인 지점(매 LLM 호출·툴 실행 직전)에서 협조적으로 멈춘다(진행 중인 HTTP 처리 자체를 강제 중단하지는 않음). 이 결과 `outcome=aborted`로 로그가 남고, `AiChatLogRepository.countSince`의 제외 조건이 `llm_failed` 하나뿐이라 **aborted도 일일 상한 차감에 포함된다**(컨트롤러 javadoc 348~350행 명시).

## 5. 프리셋 (`BrandAiPresets.java`)

### 5.1 신 6종 (2026-09-02 마케터 결정 중심 개편)

아래는 `BrandAiPresets.INSTRUCTIONS`에 등록된 presetId와 그 지시문 요지·verified 플랜이다. 서버 계약은 presetId뿐이고 버튼 문구는 FE가 `text`로 보내는 값이라 FE 확정이 정본이다. 다만 각 프리셋은 "마케터가 내리는 결정 1개"에 대응하도록 설계됐으므로(09-02 사용자 확정), 아래 **권장 문구**를 그대로 쓰면 지시문 의도와 어긋나지 않는다.

| presetId | 권장 버튼 문구 | 대응하는 결정 |
|---|---|---|
| `weekly_briefing` | 지난주에 우리 태그된 게시물 뭐 있었고 뭐가 터졌어? | 팀 공유·주간 보고 |
| `organic_fans` | 협찬 없이 우리 제품 올려준 크리에이터 중에 반응 좋은 사람 누구야? | 시딩 명단 추가 |
| `sponsored_scorecard` | 이번에 협찬한 크리에이터들 성적표 보여줘 | 재협업·증액·제외 |
| `ad_candidates` | 광고로 돌릴 만한 크리에이터 게시물 골라줘 | 파트너십 광고·사용권 요청 |
| `negative_comments` | 댓글에 불만이나 안 좋은 반응 있어? | CS 대응·크리에이터 연락 |
| `micro_creators` | 팔로워는 적은데 반응 터지는 사람 있어? | 저예산 시딩 |

| presetId | 지시문 요지(코드 요약) | verified 플랜 |
|---|---|---|
| `weekly_briefing` | 지난 7일 신규 게시물 수·조회수 톱3(작성자·조회수·협찬여부·shortCode)·전주 대비 증감을 슬랙에 붙여 쓸 수 있게 짧게 | `list_posts{days:7,sort:performance_desc,limit:10}` + `aggregate_posts{groupBy:week,days:14}` |
| `organic_fans` | 협찬 표기 없는 게시물의 작성자별 조회수 상위(표본 수 제한 없음), 게시물 1건짜리도 팬 후보에서 제외 금지, 시딩 후보 관점 | `aggregate_posts{groupBy:author,sponsorship:organic,orderBy:avgViews,limit:10}` |
| `sponsored_scorecard` | 협찬 게시물 작성자별 집계 + 협찬·오가닉 전체 평균을 기준선으로 밝히고 기준선 대비 잘함/못함 구분 | `aggregate_posts{groupBy:author,sponsorship:sponsored,orderBy:avgViews,limit:20}` + `aggregate_posts{groupBy:sponsorship}` |
| `ad_candidates` | 조회수·참여율 상위 게시물, 오가닉 우선하되 협찬 표기 게시물도 함께(협찬은 사용권 확인 필요 표기), 게시물 단위(작성자 집계 아님) | `list_posts{sort:performance_desc,limit:20}` |
| `negative_comments` | 최근 30일 반응 많은 게시물 상위 5개의 shortCode 전부를 `get_comments`에 배열로 한 번에, 업종 공통 부정 신호만 | `list_posts{days:30,sort:performance_desc,limit:5}` |
| `micro_creators` | 도달 배수 상위 작성자(표본 2건 이상) 중 팔로워 적은 순 상위 10명, 팔로워 정보 없는 작성자 제외 | `aggregate_posts{groupBy:author,orderBy:reachMultiple,limit:30,minSample:2}` |

**`micro_creators`와 화면 팔로워 필터**: 툴 인자에는 팔로워 구간 필터가 없어 서버는 도달 배수 상위 30명을 받아 팔로워 적은 순으로 고른다. 정확한 "N명 이하"가 필요하면 화면의 팔로워 상한 필터(`scope.followerMax`)를 같이 실어 보내면 된다. 팔로워 상한 툴 인자 추가는 09-02 사용자 결정으로 하지 않는다.

### 5.2 구 5종 (호환 유지, FE 전환 후 제거 예정)

`efficient_influencers`·`top_posts`·`sponsored_vs_organic`·`tagged_posts_analysis`·`paid_amplify` - `BrandAiPresets.java` 주석에 "FE 전환 전까지 호환 유지, FE가 새 버튼으로 전환을 마치면 제거"라고 명시돼 있다(28~30행). `tagged_posts_analysis`·`paid_amplify`는 verified 플랜이 없다(정성 비중이 커서 - 코드 주석).

### 5.3 미등록 presetId 폴백

`BrandAiPresets.instructionFor`·`planFor` 둘 다 `Map.getOrDefault(presetId, 빈 값)`이라 미등록 값이 와도 예외 없이 빈 지시문·빈 플랜으로 처리된다 - 컨트롤러 쪽에도 presetId에 대한 별도 검증(화이트리스트 체크)이 없다. 즉 미등록 preset은 순수 자유 질의로 폴백한다는 문서 설명이 코드와 일치한다.

## 6. 대화 (`V1BrandAiConversationController.java`)

대화 **생성**은 이 컨트롤러가 아니라 질의 엔드포인트가 한다(§2). 모든 조회·삭제는 userId 스코프 - 남의 대화·삭제된 대화는 조회든 삭제든 구분 없이 404.

### 6.1 `GET /ai/conversations?accountId=&limit=`

- `accountId`: **필수**(`@RequestParam String accountId`, 없으면 400 계열 스프링 기본 처리 - 이 컨트롤러 자체 로직은 아님). 숫자 아니면 400 "accountId가 올바르지 않습니다."
- `limit`: 선택. 기본 20(`DEFAULT_LIMIT`), 1~50 범위로 clamp(`MAX_LIMIT`). 상한 초과·미달 값도 에러 없이 clamp만 됨.
- 응답은 `meta` 없는 배열(`List<AiConversationSummary>`) - envelope의 `meta.total` 계약은 이 목록에 적용되지 않는다.

`AiConversationSummary` 필드: `id`(string), `title`, `accountIds`(string 배열이지만 실제로는 항상 요청한 `brandId` 1개짜리 - `List.of(String.valueOf(brandId))`), `updatedAt`, `messageCount`(int).

### 6.2 `GET /ai/conversations/{id}`

숫자 아닌 id는 404("대화를 찾을 수 없습니다.") - 형식 오류와 미존재를 구분하지 않는다(피드백 컨트롤러의 400과 다른 판단, §7 참조).

`AiConversationDetail` 필드: `id`, `title`, `accountIds`(대화의 `brandId` 1개짜리), `updatedAt`, `messages`(`List<AiConversationMessage>`).

`AiConversationMessage` 필드(`role`·`content`는 항상 존재, 나머지는 `@JsonInclude(NON_NULL)`이라 null이면 응답에서 키 자체가 빠짐 - envelope 전역 규칙 "nullable은 명시적 null"과 다른 이 도메인 고유 예외임에 유의):

| 필드 | null 처리 | 채워지는 메시지 |
|---|---|---|
| `role` | 항상 존재 | `"user"` 또는 `"assistant"` |
| `content` | 항상 존재 | 전부 |
| `presetId` | NON_NULL(null이면 키 생략) | user 메시지에만(질문 시 쓴 preset) |
| `messageId` | NON_NULL | **assistant 메시지에만** |
| `feedback` | **항상 노출**(NON_NULL 미적용) - "이 필드 존재 여부"가 아니라 값 자체로 상태 구분(코드 주석) | assistant 메시지에만, 저장된 피드백 없으면 값 자체가 `null` |
| `createdAt` | 항상 존재 | 전부 |
| `followUps` | NON_NULL | **대화 전체의 마지막 assistant 메시지에만** |
| `references` | NON_NULL | **대화 전체의 마지막 assistant 메시지에만** |

`feedback` 객체 필드: `{value, comment, at}` - `value`는 `"up"`/`"down"`, `comment`는 선택 코멘트(없으면 null), `at`은 저장 시각.

### 6.3 `DELETE /ai/conversations/{id}`

`conversationRepository.softDelete(...)` - **soft delete**(코드·클래스 javadoc이 명시적으로 "soft delete" 표현을 씀). 영향받은 행 0이면 404, 성공 시 204(No Content).

## 7. 피드백 (`V1BrandAiFeedbackController.java`)

`PUT`/`DELETE /v1/brand-monitoring/ai/messages/{messageId}/feedback`.

### 7.1 요청·응답

- `PUT` 요청 바디(`AiFeedbackRequest`): `{value, comment}`. `value`는 `"up"`/`"down"`만 허용, 그 외(null 포함)는 400 "value는 up 또는 down이어야 합니다." `comment`는 선택, 공백뿐이거나 빈 문자열이면 null로 정규화 저장, 500자 초과 시 400 "comment는 500자 이내여야 합니다."
- `PUT` 응답(`AiFeedbackResponse`): `{messageId, feedback: {value, comment, at}}` - `feedback` 모양은 §6.2의 `AiConversationMessage.Feedback`과 동일 레코드 공유.
- `DELETE`는 204, 세 컬럼(`feedback`·`feedback_comment`·`feedback_at`)을 전부 null로 되돌린다("피드백 취소", 클래스 javadoc).

### 7.2 에러

| 상황 | HTTP | code |
|---|---|---|
| 비로그인 | 401 | `UNAUTHORIZED` |
| `messageId`가 숫자 아님 | **400** | `VALIDATION_FAILED`("messageId가 올바르지 않습니다.") |
| `value`가 up/down 아님 | 400 | `VALIDATION_FAILED`("value는 up 또는 down이어야 합니다.") |
| `comment` 500자 초과 | 400 | `VALIDATION_FAILED`("comment는 500자 이내여야 합니다.") |
| 존재하지 않거나 남의 메시지 | 404 | `NOT_FOUND`("메시지를 찾을 수 없습니다.") |

`messageId` 형식 오류를 404가 아니라 **400**으로 처리한 것은 대화 컨트롤러(§6.2, 형식 오류도 404)와 의도적으로 다른 선택이다 - 코드 주석("여기는 태스크 계약상 명시적으로 400 - 형식 오류와 존재하지 않음을 구분해 알려준다", `V1BrandAiFeedbackController.java:98~99`).

### 7.3 멱등성

`PUT`은 멱등 - 같은 메시지에 다시 보내면 이전 피드백을 덮어쓴다(`upsert`, 클래스 javadoc 명시).

## 8. 사용량 (`V1BrandAiUsageController.java`)

`GET /v1/brand-monitoring/ai/usage`. 응답(`AiUsageResponse`): `{dailyLimit, remaining, resetAt}`.

- `dailyLimit`: app_setting `ai.chat.daily-limit`, 기본값 30(`AiChatQuota.DEFAULT_DAILY_LIMIT`).
- `remaining`: `max(0, dailyLimit - 오늘 사용 횟수)` - 0 밑으로 안 내려가게 보정됨.
- `resetAt`: KST 다음 자정(`startOfTodayKst().plusDays(1)`).
- 하루 경계는 KST 자정 기준이고, 카운트 원장은 별도 카운터 테이블이 아니라 `app.ai_chat_logs`다(설계 주석).

### 8.1 차감 규칙 (`AiChatQuota` + `AiChatLogRepository.countSince`)

`countSince`가 세는 조건은 `outcome != 'llm_failed'` 하나뿐이다(`AiChatLogRepository.java:78~83`). 즉:

| outcome | 차감 여부 |
|---|---|
| `ok`(정상 완료) | 차감 |
| `timeout`(90초 타임아웃) | **차감**(토큰이 실제 소모됐다는 이유, 컨트롤러 주석) |
| `aborted`(SSE 연결 중단) | **차감**(§4.4) |
| `tool_cap`/`llm_call_cap`(강제 답변 턴으로 마무리) | 차감(outcome이 `llm_failed`가 아니므로) |
| `blocked`(안전 필터 등으로 막힘) | 차감(outcome이 `llm_failed`가 아니므로) |
| `llm_failed`(LLM 전송 실패·인터럽트·쿼터 소진 등 순수 서버측 실패) | **미차감** |

과제 지시문의 "성공 시 차감, 타임아웃 시 차감, 순수 LLM 실패는 미차감" 규칙은 코드와 일치한다.

## 9. 에러 코드

컨트롤러 4개에서 실제 등장하는 코드·상태를 코드에서 그대로 옮긴다.

| HTTP | code | 발생 위치 | 비고 |
|---|---|---|---|
| 400 | `VALIDATION_FAILED` | `V1ApiException.validation(...)` 각지 - accountIds 개수/파싱, text 길이, scope 날짜, conversationId 형식, accountId 형식(대화 목록), messageId 형식(피드백), value/comment(피드백) | `V1ApiException.validation`은 항상 이 고정 코드 |
| 401 | `UNAUTHORIZED` | 4개 컨트롤러 전부, principal null | 메시지 "로그인이 필요해요." (일부는 "로그인이 필요합니다." - 아래 참고) |
| 404 | `NOT_FOUND` | 브랜드 미소유, 대화 없음/미소유/삭제됨, 피드백 대상 메시지 없음 | `V1ApiException.notFound(message)`는 항상 이 고정 코드 |
| 409 | `CONVERSATION_SCOPE_MISMATCH` | `resolveConversationRef` - 지정한 conversationId가 다른 브랜드 소속 | `V1ApiException.conflict("CONVERSATION_SCOPE_MISMATCH", ...)` |
| 429 | `RATE_LIMITED` | 분당 rate limiter 초과, 일일 상한 초과, 실행 풀 거절(동시 실행 상한) | 풀 거절만 `Retry-After: 10` 헤더 동반(`BUSY_RETRY_AFTER_SECONDS=10`). 나머지 둘은 Retry-After 없음 |
| 502 | `AI_UNAVAILABLE` | 90초 타임아웃, LLM 처리 실패(완결 경로·SSE 경로 공통) | `V1ApiException.badGateway("AI_UNAVAILABLE", ...)` |

401 메시지 문구는 4개 컨트롤러(`V1BrandAiMessagesController`·`V1BrandAiUsageController`·`V1BrandAiFeedbackController`·`V1BrandAiConversationController`) 전부 "로그인이 필요해요."로 동일하다(grep으로 5개 발생 지점 전부 확인).

`limitReached`(§3.3)는 에러가 아니라 정상 200 응답 안의 구조 고지 필드다 - `"time"`(85초 벽시계 예산 소진) / `"budget"`(툴 호출 상한 또는 100,000 프롬프트 토큰 예산 소진) / `null`(정상 완료, 또는 `blocked`·`aborted`처럼 애초에 이 필드가 의미 없는 경우).

## 10. 렌더링 규약 (`BrandAiPrompt.java` 시스템 프롬프트 규칙 11·11-1)

FE가 마크다운 렌더러로 `content`를 표시한다는 전제로 모델에게 강제하는 규칙이다(모델이 규칙을 어길 가능성 자체는 서버가 후처리로 걸러내지 않는다 - 프롬프트 레벨 강제뿐).

- **허용**: 굵게(`**text**`), 인라인 코드(`` `code` ``), 표, 인용(`>`), 번호 목록, 불릿(`-`), 구분선(`---`). 표는 열 5개 이하.
- **금지**: 제목(`#`/`##`/`###` 등 모든 헤더), 링크(`[]()`), 이미지, 중첩 목록, 코드 블록(``` ``` ```).
- 문단은 빈 줄로 구분.
- **자동 링크화 금지 여부**: 프롬프트에 "자동 링크화" 관련 언급 자체가 없다(링크 마크다운 문법 자체를 금지할 뿐, 평문 URL을 FE가 자동 링크화하지 말라는 서버측 지시는 없음) - 이건 애초에 FE 렌더러 구현의 문제라 백엔드 코드에 근거가 없다. **확인 필요**.
- 규칙 11-1(내부 구현 용어 유출 금지): 툴 이름·인자·필드명(`groupBy`, `sponsorship`, `reachMultiple` 등)을 답변에 쓰지 않는다. 지표는 한국어 이름만(도달 배수·참여율·표본 수·협찬·오가닉). 예외는 규칙 7의 **shortCode 표기**뿐 - 게시물을 언급할 때는 shortCode를 병기해야 한다("특정 게시물을 언급할 때는 shortCode를 함께 적습니다").

### 10.1 references와 shortCode 병기의 관계

규칙 7이 요구하는 shortCode 병기는 답변 **본문**에 요구되는 것이고, `references` 배열(§3.3)은 그 본문에 실제로 등장한 shortCode를 서버가 최대 10개까지 추려 별도 필드로 다시 실어주는 것이다(`BrandAiAgent.referencedIn` - `answer.contains(code)`로 대조, `buildReferences` - 최대 10개 컷). 즉 references는 "답변에 실제로 인용된 것만" 담고, 순서상 답변 텍스트 → references 파생이지 references → 본문 링크 생성이 아니다(애초에 링크 마크다운이 금지돼 있으므로).

### 10.2 followUps 개수 규칙

§3.3에 정리한 대로 프롬프트는 모델에게 "정확히 2개"를 요구하지만, 서버가 최종 강제하는 응답 계약은 "0~2개, 최대 2개"다 - 모델 산출이 스키마를 어기거나(kind 값 이상, text 빈 값) 파싱에 실패하면 그 항목만 버려지거나 전체가 빈 배열이 될 수 있다(`BrandAiFollowUpGenerator.parse`).

## 11. 인증

세션 쿠키 기반, 다른 v1 엔드포인트와 동일한 관용구 - [monitoring-frontend-api-spec.md §1.1](monitoring-frontend-api-spec.md)의 인증 행 참조. CSRF 헤더(`X-XSRF-TOKEN`)가 이 도메인에서 예외 없이 동일하게 요구되는지는 `SecurityConfig.java` 전체를 열어 확인하지 않아 **확인 필요**로 남긴다(§1.1 참고).

## 12. 변경 이력

`git log --oneline -- was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/` 조회 결과(short SHA):

| 날짜 | 내용 | 커밋(대표) |
|---|---|---|
| 2026-08-27 | 브랜드 모니터링 AI 챗 API·킬 스위치·배선 최초 추가, 일일 상한, 에이전트 루프 골격 | `48f2bc0a`, `1d3bf202`, `921cafec` |
| 2026-08-28 ~ 2026-08-30 | FE 변경요청서(2026-08-28) 기반 계약 전면 개편 - 질의 엔드포인트 재작성, 대화 API 추가, 사용량 API 추가, 로그·대화 저장소 확장, 한도·에러코드 정리 | `93e435e6`(질의 엔드포인트 개편), `19f3d6e3`(대화 API), `c2bd9050`(사용량 API), `0f3d527f`(저장소 확장), `d72f216a`(한도·에러코드) |
| 2026-08-30 | 대화 영속화 세부 - outcome 세분화·대화 생성 시점(F2)·검증 순서(F10)·followUps 예산(F3), 툴 조회 세션 brandId 강제 | `4258ccac`, `0d92c5b4` |
| 2026-08-31 | SSE 스트리밍 추가, 응답 계약·한계 재도출(90초·분당 10회, 시간/토큰이 1차 제약) | `277de176`(SSE 추가), `15358914`(한계 재도출), `3de3e297`(90초·분당10 상향) |
| 2026-09-02 | 프리셋 6종 개편, SSE 진행 상태(status) 단계 확장(thinking/tool/writing + label), 피드백 API(👍👎) 추가 + 날조 가드 경량화, messageId를 대화 상세·SSE done에 노출 | `8196f842`(프리셋 6종), `cbcbb1c8`(status 확장), `2a4f3a6c`(피드백 API) |

## 검증 메모 (grep 재확인)

작성 후 아래 명령으로 필드명·enum·상태코드가 실제 코드와 일치하는지 재확인했다.

```
grep -n "limitReached" was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/*.java
grep -n "RATE_LIMITED\|AI_UNAVAILABLE\|CONVERSATION_SCOPE_MISMATCH" was/src/main/java/com/celfit/was/v1/brandmonitoring/ai/*.java
```

두 grep 모두 이 문서에 적은 값과 일치하는 결과만 반환했다(상수 정의부·사용부 전부 확인).
