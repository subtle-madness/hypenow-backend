package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.common.llm.LlmQuotaExhaustedException;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.account.RateLimiter;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 브랜드 모니터링 AI 어시스턴트 질의 표면(FE 변경요청서 2026-08-28 §3·§5·§6·§7) -
 * {@code POST /v1/brand-monitoring/ai/messages}. 옛 무상태 계약({@code /ai/chat}, 이력 통째 전송)을
 * 대체한다 - 미배포 상태였어서 하위호환은 없다.
 *
 * <p>킬 스위치(설계 §7): monitoring.enabled와 monitoring.brand.ai.enabled가 둘 다 true여야 빈이
 * 등록된다. 꺼져 있으면 경로 자체가 없어 404다(브랜드 표면의 기존 게이트 관용구와 동일).
 *
 * <p>보호 장치가 세 겹이다 - 분당 버스트는 {@link RateLimiter}, 하루 총량은 {@link AiChatQuota},
 * 동시 실행은 전용 풀(brandAiChatExecutor)의 거절이다. 셋 다 429로 수렴하되 code로 구분한다.
 *
 * <p><b>F10(2026-08-30 리뷰) 검증 순서</b> - 요청 형식·대화 참조 검증(400/404/409)을 rate limiter·
 * 일일 상한보다 먼저 끝낸다. 그 두 판정은 분당·일일 버킷을 소모하지 않아야 한다 - 어차피 거부될
 * 요청이 버킷만 축내면 진짜 요청이 억울하게 429를 맞는다.
 *
 * <p><b>F2(2026-08-30 리뷰) 대화 생성 시점</b> - conversationId 미지정(신규 대화) 요청은 실행 풀이
 * 수용을 확정한 뒤에야 {@code app.ai_conversations} 행을 만든다({@link #messages}의 풀 제출 다음
 * 줄). 풀이 거절하면(동시 실행 상한 초과) 그 시점엔 아직 아무 것도 안 만들어져 있으니 로그도 대화도
 * 남기지 않는다 - 예전에는 대화부터 만들고 풀에 제출해서, 거절되면 빈 대화가 고아로 남았다.
 *
 * <p>대화(§8)는 이 컨트롤러가 만들고 갱신한다 - conversationId가 없으면 실행 풀 수용이 확정된
 * 시점(F2, 답변 성공·실패와 무관)에 새 대화를 만들고, 있으면 소유·삭제 여부와 브랜드 일치를 미리
 * 검증한다. 이력은 프론트가 다시 보내지 않는다 - {@link AiChatLogRepository#findByConversation}에서
 * 최근 6행을 복원한다.
 */
@RestController
@RequestMapping("/v1/brand-monitoring/ai")
@ConditionalOnProperty(name = {"monitoring.enabled", "monitoring.brand.ai.enabled"}, havingValue = "true")
public class V1BrandAiMessagesController {

	/** 응답 상한(스펙 §4, 2026-08-31 재도출) - 시간 뿌리 90초. SSE emitter 타임아웃·완결 경로
	 * future.get·followUps 잔여 예산이 전부 이 값에서 파생된다. */
	private static final int RESPONSE_TIMEOUT_SECONDS = 90;
	/** 분당 질문 수 기준값 - 마이그레이션이 시드한 app_setting {@value #PER_MINUTE_LIMIT_KEY}(기본값
	 * {@value #DEFAULT_PER_MINUTE_LIMIT}), 런타임 조정은 그 행 UPDATE로(AiChatQuota.DAILY_LIMIT_KEY와
	 * 동일 관용구, FE 변경요청서 2026-08-28 §9.1). 기준값 5→10 상향은 2026-08-31 한계 재도출(스펙 §4) -
	 * 운영자가 손댄 값은 마이그레이션이 존중하고 시드 기본값 그대로인 행만 올린다. */
	static final String PER_MINUTE_LIMIT_KEY = "ai.chat.per-minute-limit";
	static final int DEFAULT_PER_MINUTE_LIMIT = 10;
	private static final int MAX_TEXT_LENGTH = 2_000;
	private static final int BUSY_RETRY_AFTER_SECONDS = 10;
	/** 이력 복원 상한(FE §8) - 질문+답변 1행이 한 턴이라 6행 = 최근 6턴. 토큰 예산 보호(설계 §요구). */
	private static final int MAX_HISTORY_ROWS = 6;
	private static final int HISTORY_TRUNCATE_LENGTH = 2_000;
	/** followUps 생성을 아예 생략하는 남은 예산 하한(F3, 2026-08-30 리뷰) - 이보다 적게 남았으면 5초
	 * 예산 중 대부분을 못 쓰고 끊길 게 뻔해 시도 자체를 생략한다(빈 배열). */
	private static final long MIN_FOLLOW_UP_BUDGET_MILLIS = 1_000L;

	private static final Logger log = LoggerFactory.getLogger(V1BrandAiMessagesController.class);

	private final BrandAiAgent agent;
	private final AiChatQuota quota;
	private final AiChatLogRepository logRepository;
	private final AiConversationRepository conversationRepository;
	private final BrandAiFollowUpGenerator followUpGenerator;
	private final BrandLinkRepository linkRepository;
	private final RateLimiter rateLimiter;
	private final AppSettingRepository settingRepository;
	private final ObjectMapper objectMapper;
	// 타입을 Executor로 잡는다 - 테스트에서 동기 실행기(Runnable::run)로 갈아끼워 결정론을 얻는다
	private final Executor executor;

	public V1BrandAiMessagesController(BrandAiAgent agent, AiChatQuota quota, AiChatLogRepository logRepository,
			AiConversationRepository conversationRepository, BrandAiFollowUpGenerator followUpGenerator,
			BrandLinkRepository linkRepository, RateLimiter rateLimiter, AppSettingRepository settingRepository,
			ObjectMapper objectMapper, @Qualifier("brandAiChatExecutor") Executor executor) {
		this.agent = agent;
		this.quota = quota;
		this.logRepository = logRepository;
		this.conversationRepository = conversationRepository;
		this.followUpGenerator = followUpGenerator;
		this.linkRepository = linkRepository;
		this.rateLimiter = rateLimiter;
		this.settingRepository = settingRepository;
		this.objectMapper = objectMapper;
		this.executor = executor;
	}

	@PostMapping("/messages")
	public ApiResponse<AiMessagesResponse> messages(@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody(required = false) AiMessagesRequest request) {
		if (principal == null) {
			throw V1ApiException.unauthorized("UNAUTHORIZED", "로그인이 필요해요.");
		}
		long userId = principal.getUserId();
		Validated validated = validate(request, userId);

		// F10 - 대화 참조 검증(400/404/409)까지 끝난 뒤에야 분당·일일 버킷을 건드린다.
		ConversationRef conversationRef = resolveConversationRef(userId, request, validated.brandId());

		if (!rateLimiter.tryAcquire("ai-chat:" + userId, perMinuteLimit())) {
			throw V1ApiException.rateLimited();
		}
		quota.requireWithinDailyLimit(userId);

		// 신규 대화는 아직 id가 없다 - history가 빈 상태와 동치이므로 DB 조회 없이 새 질문 1건만 담는다.
		List<AiChatMessage> contents = conversationRef.isNew()
				? List.of(new AiChatMessage(AiChatMessage.ROLE_USER, validated.text()))
				: buildContents(conversationRef.existingId(), validated.text());
		String extraPrompt = validated.scope().summaryLine() + BrandAiPresets.instructionFor(request.presetId());

		long startedAt = System.nanoTime();
		CompletableFuture<BrandAiAgent.AgentOutcome> future;
		try {
			future = CompletableFuture.supplyAsync(
					() -> agent.run(userId, contents, validated.brandId(), validated.scope(), extraPrompt,
							BrandAiPresets.planFor(request.presetId())),
					executor);
		} catch (RejectedExecutionException e) {
			// F2 - 접수 자체가 거절됐다. 아직 대화를 만들지 않았으니(신규든 기존 검증뿐이든) 로그도 대화
			// 갱신도 하지 않는다 - 이 요청은 시스템에 아무 흔적도 남기지 않는다.
			throw V1ApiException.rateLimited(BUSY_RETRY_AFTER_SECONDS);
		}

		// F2 - 풀 수용이 확정된 뒤에야 신규 대화를 만든다. 위에서 거절됐다면 여기 도달하지 않는다.
		long conversationId = conversationRef.resolve(conversationRepository, userId, validated.brandId(),
				validated.text());

		BrandAiAgent.AgentOutcome outcome;
		try {
			outcome = awaitOutcome(future, userId);
		} catch (ChatFailure failure) {
			logRepository.insert(new AiChatLogEntry(userId, validated.brandId(), validated.text(), null, List.of(),
					0, 0, elapsedMillis(startedAt), failure.outcome(), conversationId,
					request.presetId(), scopeJson(request), emptyArray(), emptyArray()));
			conversationRepository.touch(conversationId);
			throw failure.apiException();
		}

		// F3 - followUps는 90초 응답 예산 안에서만 만든다(공통 로직은 followUpsFor로 추출 - SSE 경로도
		// 그대로 재사용한다, T4).
		List<AiMessagesResponse.FollowUp> followUps = followUpsFor(outcome, validated.text(), startedAt);
		List<AiMessagesResponse.Reference> references = toReferences(outcome);

		Long messageId = logRepository.insert(new AiChatLogEntry(userId, outcome.brandId() != null
				? outcome.brandId() : validated.brandId(), validated.text(), outcome.answer(), outcome.toolCalls(),
				outcome.promptTokens(), outcome.outputTokens(), elapsedMillis(startedAt), outcome.outcome(),
				conversationId, request.presetId(), scopeJson(request), objectMapper.valueToTree(followUps),
				objectMapper.valueToTree(references)));
		conversationRepository.touch(conversationId);

		return ApiResponse.ok(new AiMessagesResponse(String.valueOf(conversationId),
				messageId == null ? null : String.valueOf(messageId), outcome.answer(), followUps, references,
				outcome.limitReached()));
	}

	/**
	 * SSE 스트리밍 질의(T4, FE 변경요청서 §3.2) - 같은 경로·같은 메서드를 Accept: text/event-stream
	 * 협상으로 나눈 변형이다({@code produces} 조건으로 스프링이 라우팅한다). 사전 검증(400/404/409/429)은
	 * SSE를 열기 전이므로 완결 JSON 경로와 동일한 {@link V1ApiException}을 던지되, 이 메서드는 그
	 * 예외를 전역 {@code V1ExceptionAdvice}에 맡기지 않고 직접 JSON으로 써서 응답한다 -
	 * {@link #writeJsonError} 참조.
	 *
	 * <p>실행 풀 제출·F2 대화 생성 시점(풀 수용 확정 후에만 INSERT)·90초 데드라인·followUps 잔여 예산·
	 * 로그 적재·touch는 전부 완결 경로와 같은 원칙을 공유한다({@link #followUpsFor}·{@link #toReferences}
	 * 공통 메서드, 그리고 {@link SseEmitter}의 타임아웃을 {@value #RESPONSE_TIMEOUT_SECONDS}초로 맞춘
	 * 것) - 다만 완결 경로가 {@code future.get(90, SECONDS)}로 요청 스레드에서 동기 대기하는 것과 달리,
	 * 이 경로는 이미 전용 실행기 스레드 안에서 실행되므로 그 데드라인 역할을 {@link SseEmitter}의 자체
	 * 타임아웃(만료 시 onTimeout → abort 플래그)이 대신한다.
	 *
	 * <p><b>메시지 필드 차이(FE 문서와 의도적 불일치, 보고에 명시할 것)</b> - messageId는 로그 적재가
	 * 실행 끝에 일어나므로 {@code meta} 이벤트가 아니라 {@code done} 이벤트에 싣는다.
	 */
	@PostMapping(value = "/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter messagesStream(@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody(required = false) AiMessagesRequest request, HttpServletResponse response)
			throws IOException {
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setHeader("X-Accel-Buffering", "no");

		long userId;
		Validated validated;
		ConversationRef conversationRef;
		try {
			if (principal == null) {
				throw V1ApiException.unauthorized("UNAUTHORIZED", "로그인이 필요해요.");
			}
			userId = principal.getUserId();
			validated = validate(request, userId);
			conversationRef = resolveConversationRef(userId, request, validated.brandId());
			if (!rateLimiter.tryAcquire("ai-chat:" + userId, perMinuteLimit())) {
				throw V1ApiException.rateLimited();
			}
			quota.requireWithinDailyLimit(userId);
		} catch (V1ApiException e) {
			writeJsonError(response, e);
			return null;
		}

		SseEmitter emitter = new SseEmitter(RESPONSE_TIMEOUT_SECONDS * 1_000L);
		AtomicBoolean aborted = new AtomicBoolean(false);
		// T4 - 클라이언트 연결 중단(탭 종료·새로고침 등)·자체 타임아웃 만료·정상 완료 어느 쪽이든
		// 이후 send()는 전부 무해하게 건너뛰도록 aborted를 세운다. 에이전트 루프는 이 플래그를
		// LLM 호출·툴 실행 사이마다 확인해 협조적으로 멈춘다(BrandAiAgent#run 스트리밍 오버로드).
		emitter.onTimeout(() -> aborted.set(true));
		emitter.onError(e -> aborted.set(true));
		emitter.onCompletion(() -> aborted.set(true));

		long finalUserId = userId;
		Validated finalValidated = validated;
		ConversationRef finalConversationRef = conversationRef;
		try {
			executor.execute(() -> runSse(emitter, aborted, finalUserId, finalConversationRef, finalValidated,
					request));
		} catch (RejectedExecutionException e) {
			// F2와 동형 - 접수 자체가 거절됐다. emitter는 아직 반환 전이라 클라이언트에 아무 것도
			// 나가지 않았으니 JSON 429로 정리한다(대화도 로그도 만들지 않는다).
			writeJsonError(response, V1ApiException.rateLimited(BUSY_RETRY_AFTER_SECONDS));
			return null;
		}
		return emitter;
	}

	/**
	 * SSE를 열기 전 검증 실패를 JSON으로 직접 쓴다(T4) - {@code /messages}의 SSE 변형은
	 * {@code produces=text/event-stream}이라 요청 Accept가 정확히 그 값뿐이면 전역
	 * {@code V1ExceptionAdvice}(콘텐츠 협상 기반)에 맡길 경우 406으로 뒤바뀔 위험이 있다(스프링이
	 * 예외 처리 시에도 원 매핑의 producible media type을 기억해 그대로 강제한다). 완결 JSON 경로와
	 * 같은 {@link ApiResponse#fail} envelope를 그대로 쓰되 메시지 컨버터 협상을 거치지 않고
	 * 서블릿 응답에 직접 쓴다.
	 */
	private void writeJsonError(HttpServletResponse response, V1ApiException e) throws IOException {
		response.setStatus(e.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		// 서블릿 writer의 기본 인코딩은 ISO-8859-1이라 명시하지 않으면 한글 메시지가 깨진다
		// (2026-09-01 실측 - 일일 한도 429 문구 mojibake). 컨버터를 안 거치는 이 경로만의 의무다.
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		if (e.retryAfterSeconds() != null) {
			response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
		}
		response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(e.code(), e.getMessage())));
	}

	/**
	 * SSE 실행 본체(T4) - 전용 실행기 스레드에서 돈다(이미 풀 수용이 확정된 뒤이므로 F2와 동형으로
	 * 여기서 신규 대화를 만든다). meta → (status·delta 반복) → done 순으로 이벤트를 보낸다. 실패·
	 * 중단 시에는 error 이벤트 또는 로그만 남기고 done 없이 끝낸다.
	 *
	 * <p><b>status 이벤트 확장(2026-09-02, 진행 상태 세분화)</b> - stage는 세 가지다: {@code thinking}
	 * (매 LLM 호출 직전, index=몇 번째 LLM 호출인지 1부터 - 날조 방지 재시도 턴도 다시 나간다),
	 * {@code tool}(툴 실행 직전, tool·index=몇 번째 툴 호출인지 1부터), {@code writing}(홀드백 중인
	 * 일반 턴에서 텍스트가 처음 도착한 시점, 그 턴에 1회). 전부 FE가 그대로 쓸 수 있는 한국어 문구를
	 * label에 싣는다({@link BrandAiToolSpecs#labelFor}가 tool 문구의 정본). 본문 방출 방식(홀드백)은
	 * 그대로다 - meta 직후 첫 thinking이 나가고, 툴 없이 끝나는 마지막 턴은
	 * thinking → writing → delta(누적 답변 일괄) → done 순서가 된다. 예시:
	 * {@code {"stage":"thinking","index":1,"label":"생각하는 중"}} →
	 * {@code {"stage":"tool","tool":"list_posts","index":1,"label":"게시물 찾는 중"}} →
	 * {@code {"stage":"thinking","index":2,"label":"생각하는 중"}} →
	 * {@code {"stage":"writing","label":"답변 정리하는 중"}}.
	 */
	private void runSse(SseEmitter emitter, AtomicBoolean aborted, long userId, ConversationRef conversationRef,
			Validated validated, AiMessagesRequest request) {
		long startedAt = System.nanoTime();
		long conversationId = conversationRef.resolve(conversationRepository, userId, validated.brandId(),
				validated.text());
		sendEvent(emitter, aborted, "meta",
				objectMapper.createObjectNode().put("conversationId", String.valueOf(conversationId)));

		List<AiChatMessage> contents = conversationRef.isNew()
				? List.of(new AiChatMessage(AiChatMessage.ROLE_USER, validated.text()))
				: buildContents(conversationId, validated.text());
		String extraPrompt = validated.scope().summaryLine() + BrandAiPresets.instructionFor(request.presetId());

		BrandAiAgent.StreamListener listener = new BrandAiAgent.StreamListener() {
			@Override
			public void onAnswerDelta(String textDelta) {
				sendEvent(emitter, aborted, "delta", objectMapper.createObjectNode().put("text", textDelta));
			}

			@Override
			public void onToolCall(String toolName, int index) {
				sendEvent(emitter, aborted, "status", objectMapper.createObjectNode().put("stage", "tool")
						.put("tool", toolName).put("index", index)
						.put("label", BrandAiToolSpecs.labelFor(toolName)));
			}

			@Override
			public void onThinking(int llmCallIndex) {
				sendEvent(emitter, aborted, "status", objectMapper.createObjectNode().put("stage", "thinking")
						.put("index", llmCallIndex).put("label", "생각하는 중"));
			}

			@Override
			public void onWriting() {
				sendEvent(emitter, aborted, "status",
						objectMapper.createObjectNode().put("stage", "writing").put("label", "답변 정리하는 중"));
			}
		};

		BrandAiAgent.AgentOutcome outcome;
		try {
			outcome = agent.run(userId, contents, validated.brandId(), validated.scope(), extraPrompt,
					BrandAiPresets.planFor(request.presetId()), listener, aborted::get);
		} catch (RuntimeException e) {
			if (e instanceof LlmQuotaExhaustedException) {
				log.warn("AI 챗 스트리밍 Vertex 쿼터 소진 - userId={}", userId);
			} else {
				log.error("AI 챗 스트리밍 처리 실패 - userId={}", userId, e);
			}
			logRepository.insert(new AiChatLogEntry(userId, validated.brandId(), validated.text(), null, List.of(),
					0, 0, elapsedMillis(startedAt), AiChatLogEntry.OUTCOME_LLM_FAILED, conversationId,
					request.presetId(), scopeJson(request), emptyArray(), emptyArray()));
			conversationRepository.touch(conversationId);
			sendEvent(emitter, aborted, "error", objectMapper.createObjectNode().put("code", "AI_UNAVAILABLE")
					.put("message", "답변을 받지 못했어요. 잠시 후 다시 시도해 주세요."));
			emitter.complete();
			return;
		}

		if (AiChatLogEntry.OUTCOME_ABORTED.equals(outcome.outcome())) {
			// T4 - 유저 중단도 일일 상한 차감에 포함한다(countSince 제외 조건이 llm_failed 하나뿐이라
			// 자동으로 포함된다). followUps는 생략 - 이미 끊긴 연결에 보낼 이유가 없다.
			logRepository.insert(new AiChatLogEntry(userId,
					outcome.brandId() != null ? outcome.brandId() : validated.brandId(), validated.text(), null,
					outcome.toolCalls(), outcome.promptTokens(), outcome.outputTokens(), elapsedMillis(startedAt),
					AiChatLogEntry.OUTCOME_ABORTED, conversationId, request.presetId(), scopeJson(request),
					emptyArray(), emptyArray()));
			conversationRepository.touch(conversationId);
			emitter.complete();
			return;
		}

		List<AiMessagesResponse.FollowUp> followUps = followUpsFor(outcome, validated.text(), startedAt);
		List<AiMessagesResponse.Reference> references = toReferences(outcome);

		Long messageId = logRepository.insert(new AiChatLogEntry(userId,
				outcome.brandId() != null ? outcome.brandId() : validated.brandId(), validated.text(),
				outcome.answer(), outcome.toolCalls(), outcome.promptTokens(), outcome.outputTokens(),
				elapsedMillis(startedAt), outcome.outcome(), conversationId, request.presetId(), scopeJson(request),
				objectMapper.valueToTree(followUps), objectMapper.valueToTree(references)));
		conversationRepository.touch(conversationId);

		// T4 - messageId는 로그 적재가 끝에 일어나므로 meta가 아니라 done에 싣는다(FE 문서와의 의도적
		// 차이, 완료 보고에 명시).
		ObjectNode doneNode = objectMapper.createObjectNode();
		doneNode.put("messageId", messageId == null ? null : String.valueOf(messageId));
		doneNode.set("followUps", objectMapper.valueToTree(followUps));
		doneNode.set("references", objectMapper.valueToTree(references));
		doneNode.put("limitReached", outcome.limitReached());
		sendEvent(emitter, aborted, "done", doneNode);
		emitter.complete();
	}

	/** SSE 이벤트 전송(T4) - 이미 중단된 연결이면 보내지 않고, 전송 실패는 중단으로 접는다(더 이상
	 * 이 emitter에 쓰지 않게). 데이터는 메시지 컨버터 협상 없이 문자열로 직접 실어 보낸다 - 이 표면이
	 * 쓰는 JSON 매퍼(tools.jackson)와 스프링 기본 SSE 컨버터 배선이 어긋날 여지를 없앤다. */
	private void sendEvent(SseEmitter emitter, AtomicBoolean aborted, String eventName, JsonNode data) {
		if (aborted.get()) {
			return;
		}
		try {
			emitter.send(SseEmitter.event().name(eventName).data(objectMapper.writeValueAsString(data)));
		} catch (IOException | IllegalStateException e) {
			aborted.set(true);
		}
	}

	/** followUps 생성 공통 로직(F3, T4 리팩터) - 완결 JSON·SSE 두 경로가 그대로 공유한다. 90초 응답
	 * 예산 중 남은 시간을 계산해 min(5초, 남은 예산)만 주고, 1초 미만 남았으면 생성 자체를 생략한다
	 * (빈 배열) - 완성된 답변을 followUps 때문에 더 늦게 돌려줄 이유가 없다. */
	private List<AiMessagesResponse.FollowUp> followUpsFor(BrandAiAgent.AgentOutcome outcome, String question,
			long startedAtNanos) {
		long remainingBudgetMillis = RESPONSE_TIMEOUT_SECONDS * 1_000L - elapsedMillis(startedAtNanos);
		return outcome.answered() && remainingBudgetMillis >= MIN_FOLLOW_UP_BUDGET_MILLIS
				? followUpGenerator.generate(question, outcome.answer(), remainingBudgetMillis)
				: List.of();
	}

	/** references 응답 조립 공통 로직(T4 리팩터) - 완결 JSON·SSE 두 경로가 그대로 공유한다. */
	private static List<AiMessagesResponse.Reference> toReferences(BrandAiAgent.AgentOutcome outcome) {
		return outcome.references().stream()
				.map(ref -> new AiMessagesResponse.Reference(AiMessagesResponse.Reference.TYPE_POST, ref.shortCode(),
						ref.label()))
				.toList();
	}

	/**
	 * 전용 풀에서 이미 돌고 있는 작업을 90초에 끊는다(C2). {@code future.cancel(true)}는 실행 중인 작업을
	 * 인터럽트하지 <b>않는다</b> - {@link CompletableFuture}의 {@code cancel}은 명세상
	 * {@code mayInterruptIfRunning}을 무시하고 항상 미래(future)만 예외적으로 완료시키므로, 이 호출
	 * 뒤에도 작업 스레드는 계속 돈다(2 스레드 풀이라 반복되면 여전히 429 위험이 남는다는 한계가
	 * 있다). 실질적인 방어는 여기가 아니라 에이전트 내부 벽시계 예산(BrandAiAgent, 85초, 2026-08-31
	 * 한계 재도출)과 Vertex 요청 타임아웃 단축(BrandAiConfig, 45초)이고, 이 90초 get()은 그 두
	 * 안전장치가 실패했을 때 최소한 HTTP 응답만이라도 제때 끊어 사용자를 기다리게 하지 않는 최후
	 * 방어선이다.
	 *
	 * <p><b>F2(2026-08-30 리뷰) outcome 세분화</b> - 실패 원인별로 {@link ChatFailure}에 실린 outcome이
	 * 갈린다: 타임아웃은 {@link AiChatLogEntry#OUTCOME_TIMEOUT}(토큰이 실제 소모됐으므로 일일 상한
	 * 차감 대상에 포함 - {@link AiChatLogRepository#countSince}의 제외 조건이 llm_failed 하나뿐이라
	 * 자동으로 포함된다), 그 외(인터럽트·LLM 전송 실패·쿼터 소진 등)는 전부
	 * {@link AiChatLogEntry#OUTCOME_LLM_FAILED}(차감 제외)다. 실행 풀 거절
	 * ({@link RejectedExecutionException})은 이 메서드 호출 전에 {@link #messages}가 이미 처리하므로
	 * 여기 도달하지 않는다 - 로그를 아예 남기지 않는 유일한 실패 경로다.
	 */
	private BrandAiAgent.AgentOutcome awaitOutcome(CompletableFuture<BrandAiAgent.AgentOutcome> future,
			long userId) {
		try {
			return future.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			log.warn("AI 챗 응답 시간 초과({}초) - userId={}", RESPONSE_TIMEOUT_SECONDS, userId);
			// 코드는 FE 계약(변경요청서 §9.1)에 맞춰 AI_UNAVAILABLE로 통일한다 - 타임아웃도 결국 "지금은
			// 답변을 못 받았다"는 같은 사용자 경험이라 LLM 실패 경로와 코드를 나눌 이유가 없다(outcome은
			// 나뉘지만 HTTP 응답 code는 그대로 하나다).
			throw new ChatFailure(AiChatLogEntry.OUTCOME_TIMEOUT, V1ApiException.badGateway("AI_UNAVAILABLE",
					"답변 생성이 너무 오래 걸렸어요. 잠시 후 다시 시도해 주세요."));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ChatFailure(AiChatLogEntry.OUTCOME_LLM_FAILED,
					V1ApiException.badGateway("AI_UNAVAILABLE", "답변을 받지 못했어요. 잠시 후 다시 시도해 주세요."));
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof V1ApiException v1) {
				throw new ChatFailure(AiChatLogEntry.OUTCOME_LLM_FAILED, v1);
			}
			if (cause instanceof LlmQuotaExhaustedException) {
				log.warn("AI 챗 Vertex 쿼터 소진 - userId={}", userId);
			} else {
				log.error("AI 챗 처리 실패 - userId={}", userId, cause);
			}
			throw new ChatFailure(AiChatLogEntry.OUTCOME_LLM_FAILED,
					V1ApiException.badGateway("AI_UNAVAILABLE", "답변을 받지 못했어요. 잠시 후 다시 시도해 주세요."));
		}
	}

	/**
	 * {@link #awaitOutcome} 실패를 outcome 문자열과 함께 실어 나르는 unchecked 예외(F2, 2026-08-30
	 * 리뷰) - 호출부({@link #messages})가 이 outcome을 그대로 로그에 적재한 뒤 apiException을 던진다.
	 */
	private static final class ChatFailure extends RuntimeException {
		private final String outcome;
		private final V1ApiException apiException;

		ChatFailure(String outcome, V1ApiException apiException) {
			super(apiException.getMessage());
			this.outcome = outcome;
			this.apiException = apiException;
		}

		String outcome() {
			return outcome;
		}

		V1ApiException apiException() {
			return apiException;
		}
	}

	/** 검증 통과 후 값 - brandId는 accountIds[0]을 파싱·소유 검증까지 마친 값이다. */
	private record Validated(long brandId, String text, AiScope scope) {
	}

	/**
	 * accountIds 정확히 1개(그 외 400)·숫자 파싱(실패 400)·소유 검증(미소유 404)·text 길이(1~2,000자)·
	 * scope 파싱(날짜 형식 오류 400, {@link AiScope#from})을 한 번에 검증한다(T1, FE §5).
	 */
	private Validated validate(AiMessagesRequest request, long userId) {
		if (request == null) {
			throw V1ApiException.validation("요청 본문이 비어 있어요.");
		}
		List<String> accountIds = request.accountIds();
		if (accountIds == null || accountIds.size() != 1) {
			throw V1ApiException.validation("accountIds는 정확히 1개여야 해요.");
		}
		long brandId;
		try {
			brandId = Long.parseLong(accountIds.get(0));
		} catch (NumberFormatException e) {
			throw V1ApiException.validation("accountIds가 올바르지 않아요.");
		}
		if (linkRepository.findActiveByUserAndBrand(userId, brandId).isEmpty()) {
			throw V1ApiException.notFound("그 브랜드를 찾을 수 없어요.");
		}
		String text = request.text();
		if (text == null || text.isBlank()) {
			throw V1ApiException.validation("질문을 입력해 주세요.");
		}
		if (text.length() > MAX_TEXT_LENGTH) {
			throw V1ApiException.validation("메시지가 너무 길어요. 더 짧게 나눠서 물어봐 주세요.");
		}
		AiScope scope = AiScope.from(request.scope());
		return new Validated(brandId, text, scope);
	}

	/**
	 * 대화 참조 해석(T2, F2·F10 2026-08-30 리뷰) - conversationId 미지정이면 새 대화를 뜻하지만, 실제
	 * INSERT는 여기서 하지 않는다({@link ConversationRef#resolve} - 실행 풀이 수용을 확정한 뒤로 미뤄
	 * 거절 시 빈 대화가 남는 사고를 막는다, F2). conversationId가 지정됐으면 소유·미삭제(없으면 404)와
	 * 브랜드 일치(다르면 409 CONVERSATION_SCOPE_MISMATCH)를 여기서 전부 검증한다 - 이 검증까지 끝난
	 * 뒤에만 분당 rate limiter를 소모하도록(F10) 컨트롤러가 이 메서드를 rate limiter보다 먼저 부른다.
	 */
	private ConversationRef resolveConversationRef(long userId, AiMessagesRequest request, long brandId) {
		String rawId = request.conversationId();
		if (rawId == null || rawId.isBlank()) {
			return ConversationRef.NEW;
		}
		long conversationId;
		try {
			conversationId = Long.parseLong(rawId);
		} catch (NumberFormatException e) {
			throw V1ApiException.validation("conversationId가 올바르지 않아요.");
		}
		AiConversationRepository.ConversationRow row = conversationRepository.findOwnedActive(conversationId, userId)
				.orElseThrow(() -> V1ApiException.notFound("대화를 찾을 수 없어요."));
		if (row.brandId() != brandId) {
			throw V1ApiException.conflict("CONVERSATION_SCOPE_MISMATCH", "이 대화는 다른 브랜드에 속해 있어요.");
		}
		return new ConversationRef(conversationId);
	}

	/**
	 * 대화 참조(F2, 2026-08-30 리뷰) - {@code existingId == 0}이면 신규(아직 INSERT 전), 그 외면 이미
	 * 검증을 마친 기존 대화 id다. bigserial 시작값이 1이라 0은 안전한 미생성 센티널이다
	 * ({@link com.celfit.was.v1.brandmonitoring.ai.BrandAiToolbox}의 brandId 센티널 관용구와 동일).
	 */
	private record ConversationRef(long existingId) {
		static final ConversationRef NEW = new ConversationRef(0L);

		boolean isNew() {
			return existingId == 0L;
		}

		/** 신규면 이 시점에 INSERT해 id를 얻는다(실행 풀 수용 확정 후에만 호출돼야 한다, F2). 기존이면
		 * {@link #resolveConversationRef}가 이미 검증한 id를 그대로 돌려준다. */
		long resolve(AiConversationRepository repository, long userId, long brandId, String text) {
			return isNew() ? repository.create(userId, brandId, text) : existingId;
		}
	}

	/**
	 * 이력 복원(T2, FE §8) - 최근 {@value #MAX_HISTORY_ROWS}행(질문+답변 쌍)을 대화 로그에서 복원해
	 * 새 질문 뒤에 붙인다. answer가 없는 행(진행 중 실패 등)은 user 발화만 넣는다. 각 content는
	 * {@value #HISTORY_TRUNCATE_LENGTH}자로 절단한다(토큰 예산 보호) - 새 질문 자체는 이미 컨트롤러가
	 * 길이 검증을 마쳤으니 절단하지 않는다. 신규 대화(아직 id 없음)는 이 메서드를 타지 않는다 - 호출부가
	 * 빈 이력과 동치인 새 질문 1건짜리 목록으로 대신한다.
	 */
	private List<AiChatMessage> buildContents(long conversationId, String newText) {
		List<AiChatLogRepository.ConversationMessageRow> rows = logRepository.findByConversation(conversationId);
		List<AiChatLogRepository.ConversationMessageRow> recent = rows.size() <= MAX_HISTORY_ROWS ? rows
				: rows.subList(rows.size() - MAX_HISTORY_ROWS, rows.size());
		List<AiChatMessage> messages = new ArrayList<>();
		for (AiChatLogRepository.ConversationMessageRow row : recent) {
			messages.add(new AiChatMessage(AiChatMessage.ROLE_USER, truncate(row.question())));
			if (row.answer() != null) {
				messages.add(new AiChatMessage(AiChatMessage.ROLE_ASSISTANT, truncate(row.answer())));
			}
		}
		messages.add(new AiChatMessage(AiChatMessage.ROLE_USER, newText));
		return messages;
	}

	private static String truncate(String text) {
		if (text == null) {
			return "";
		}
		if (text.codePointCount(0, text.length()) <= HISTORY_TRUNCATE_LENGTH) {
			return text;
		}
		int cut = text.offsetByCodePoints(0, HISTORY_TRUNCATE_LENGTH);
		return text.substring(0, cut) + "...";
	}

	/** 로그의 scope 컬럼(§계측 정본) - 요청 scope 원문 그대로 저장한다(정규화한 {@link AiScope}가 아니다). */
	private JsonNode scopeJson(AiMessagesRequest request) {
		return request.scope() == null ? null : objectMapper.valueToTree(request.scope());
	}

	private JsonNode emptyArray() {
		return objectMapper.createArrayNode();
	}

	private static long elapsedMillis(long startedAtNanos) {
		return (System.nanoTime() - startedAtNanos) / 1_000_000L;
	}

	/** app_setting {@value #PER_MINUTE_LIMIT_KEY} 조회 - AiChatQuota.dailyLimit()과 동일한 폴백
	 * 관용구(캐시 없이 매번 조회, 숫자가 아니면 기본값). */
	private int perMinuteLimit() {
		Optional<String> stored = settingRepository.findValue(PER_MINUTE_LIMIT_KEY);
		if (stored.isEmpty()) {
			return DEFAULT_PER_MINUTE_LIMIT;
		}
		try {
			return Integer.parseInt(stored.get().trim());
		} catch (NumberFormatException e) {
			log.warn("{} 값이 숫자가 아님({}) - 기본값 {}로 폴백", PER_MINUTE_LIMIT_KEY, stored.get(),
					DEFAULT_PER_MINUTE_LIMIT);
			return DEFAULT_PER_MINUTE_LIMIT;
		}
	}
}
