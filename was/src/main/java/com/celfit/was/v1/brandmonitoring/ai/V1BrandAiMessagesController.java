package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.common.llm.LlmQuotaExhaustedException;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.account.RateLimiter;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
 * <p>대화(§8)는 이 컨트롤러가 만들고 갱신한다 - conversationId가 없으면 질문 접수 시점(답변 성공·실패와
 * 무관하게)에 새 대화를 만들고, 있으면 소유·삭제 여부와 브랜드 일치를 검증한다. 이력은 프론트가 다시
 * 보내지 않는다 - {@link AiChatLogRepository#findByConversation}에서 최근 6행을 복원한다.
 */
@RestController
@RequestMapping("/v1/brand-monitoring/ai")
@ConditionalOnProperty(name = {"monitoring.enabled", "monitoring.brand.ai.enabled"}, havingValue = "true")
public class V1BrandAiMessagesController {

	/** 동기 응답 상한(설계 §5). */
	private static final int RESPONSE_TIMEOUT_SECONDS = 60;
	/** 분당 질문 수 기준값 - 마이그레이션이 시드한 app_setting {@value #PER_MINUTE_LIMIT_KEY}(기본값
	 * {@value #DEFAULT_PER_MINUTE_LIMIT}), 런타임 조정은 그 행 UPDATE로(AiChatQuota.DAILY_LIMIT_KEY와
	 * 동일 관용구, FE 변경요청서 2026-08-28 §9.1). */
	static final String PER_MINUTE_LIMIT_KEY = "ai.chat.per-minute-limit";
	static final int DEFAULT_PER_MINUTE_LIMIT = 5;
	private static final int MAX_TEXT_LENGTH = 2_000;
	private static final int BUSY_RETRY_AFTER_SECONDS = 10;
	/** 이력 복원 상한(FE §8) - 질문+답변 1행이 한 턴이라 6행 = 최근 6턴. 토큰 예산 보호(설계 §요구). */
	private static final int MAX_HISTORY_ROWS = 6;
	private static final int HISTORY_TRUNCATE_LENGTH = 2_000;

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

		if (!rateLimiter.tryAcquire("ai-chat:" + userId, perMinuteLimit())) {
			throw V1ApiException.rateLimited();
		}
		quota.requireWithinDailyLimit(userId);

		long conversationId = resolveConversationId(userId, request, validated.brandId());
		List<AiChatMessage> contents = buildContents(conversationId, validated.text());
		String extraPrompt = validated.scope().summaryLine() + BrandAiPresets.instructionFor(request.presetId());

		long startedAt = System.nanoTime();
		BrandAiAgent.AgentOutcome outcome;
		try {
			outcome = runWithTimeout(userId, contents, validated.scope(), extraPrompt);
		} catch (RuntimeException e) {
			logRepository.insert(new AiChatLogEntry(userId, validated.brandId(), validated.text(), null, List.of(),
					0, 0, elapsedMillis(startedAt), AiChatLogEntry.OUTCOME_LLM_FAILED, conversationId,
					request.presetId(), scopeJson(request), emptyArray(), emptyArray()));
			conversationRepository.touch(conversationId);
			throw e;
		}

		List<AiMessagesResponse.FollowUp> followUps = outcome.answered()
				? followUpGenerator.generate(validated.text(), outcome.answer())
				: List.of();
		List<AiMessagesResponse.Reference> references = outcome.references().stream()
				.map(ref -> new AiMessagesResponse.Reference(AiMessagesResponse.Reference.TYPE_POST, ref.shortCode(),
						ref.label()))
				.toList();

		Long messageId = logRepository.insert(new AiChatLogEntry(userId, outcome.brandId() != null
				? outcome.brandId() : validated.brandId(), validated.text(), outcome.answer(), outcome.toolCalls(),
				outcome.promptTokens(), outcome.outputTokens(), elapsedMillis(startedAt), outcome.outcome(),
				conversationId, request.presetId(), scopeJson(request), objectMapper.valueToTree(followUps),
				objectMapper.valueToTree(references)));
		conversationRepository.touch(conversationId);

		return ApiResponse.ok(new AiMessagesResponse(String.valueOf(conversationId),
				messageId == null ? null : String.valueOf(messageId), outcome.answer(), followUps, references));
	}

	/**
	 * 전용 풀에서 돌리고 60초에 끊는다(C2). {@code future.cancel(true)}는 실행 중인 작업을
	 * 인터럽트하지 <b>않는다</b> - {@link CompletableFuture}의 {@code cancel}은 명세상
	 * {@code mayInterruptIfRunning}을 무시하고 항상 미래(future)만 예외적으로 완료시키므로, 이 호출
	 * 뒤에도 작업 스레드는 계속 돈다(2 스레드 풀이라 반복되면 여전히 429 위험이 남는다는 한계가
	 * 있다). 실질적인 방어는 여기가 아니라 에이전트 내부 벽시계 예산(BrandAiAgent, 55초)과 Vertex
	 * 요청 타임아웃 단축(BrandAiConfig, 45초)이고, 이 60초 get()은 그 두 안전장치가 실패했을 때
	 * 최소한 HTTP 응답만이라도 제때 끊어 사용자를 기다리게 하지 않는 최후 방어선이다.
	 */
	private BrandAiAgent.AgentOutcome runWithTimeout(long userId, List<AiChatMessage> contents, AiScope scope,
			String extraPrompt) {
		CompletableFuture<BrandAiAgent.AgentOutcome> future;
		try {
			future = CompletableFuture.supplyAsync(() -> agent.run(userId, contents, scope, extraPrompt), executor);
		} catch (RejectedExecutionException e) {
			throw V1ApiException.rateLimited(BUSY_RETRY_AFTER_SECONDS);
		}
		try {
			return future.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			log.warn("AI 챗 응답 시간 초과({}초) - userId={}", RESPONSE_TIMEOUT_SECONDS, userId);
			// 코드는 FE 계약(변경요청서 §9.1)에 맞춰 AI_UNAVAILABLE로 통일한다 - 타임아웃도 결국 "지금은
			// 답변을 못 받았다"는 같은 사용자 경험이라 LLM 실패 경로와 코드를 나눌 이유가 없다.
			throw V1ApiException.badGateway("AI_UNAVAILABLE", "답변 생성이 너무 오래 걸렸어요. 잠시 후 다시 시도해 주세요.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw V1ApiException.badGateway("AI_UNAVAILABLE", "답변을 받지 못했어요. 잠시 후 다시 시도해 주세요.");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof V1ApiException v1) {
				throw v1;
			}
			if (cause instanceof LlmQuotaExhaustedException) {
				log.warn("AI 챗 Vertex 쿼터 소진 - userId={}", userId);
			} else {
				log.error("AI 챗 처리 실패 - userId={}", userId, cause);
			}
			throw V1ApiException.badGateway("AI_UNAVAILABLE", "답변을 받지 못했어요. 잠시 후 다시 시도해 주세요.");
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
	 * 대화 해석(T2, FE §8) - conversationId 미지정이면 즉시 새 대화를 만든다(답변 성공·실패와 무관하게
	 * 질문이 접수됐다는 뜻이다). 지정됐으면 소유·미삭제(없으면 404)와 브랜드 일치(다르면 409
	 * CONVERSATION_SCOPE_MISMATCH)를 검증한다.
	 */
	private long resolveConversationId(long userId, AiMessagesRequest request, long brandId) {
		String rawId = request.conversationId();
		if (rawId == null || rawId.isBlank()) {
			return conversationRepository.create(userId, brandId, request.text());
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
		return conversationId;
	}

	/**
	 * 이력 복원(T2, FE §8) - 최근 {@value #MAX_HISTORY_ROWS}행(질문+답변 쌍)을 대화 로그에서 복원해
	 * 새 질문 뒤에 붙인다. answer가 없는 행(진행 중 실패 등)은 user 발화만 넣는다. 각 content는
	 * {@value #HISTORY_TRUNCATE_LENGTH}자로 절단한다(토큰 예산 보호) - 새 질문 자체는 이미 컨트롤러가
	 * 길이 검증을 마쳤으니 절단하지 않는다.
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
