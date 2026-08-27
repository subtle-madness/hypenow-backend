package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.common.llm.LlmQuotaExhaustedException;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.account.RateLimiter;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import java.util.List;
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

/**
 * 브랜드 모니터링 AI 어시스턴트 챗 표면(설계 §5) - 무상태 동기 API 하나뿐이다.
 *
 * <p>킬 스위치(설계 §7): monitoring.enabled와 monitoring.brand.ai.enabled가 둘 다 true여야 빈이
 * 등록된다. 꺼져 있으면 경로 자체가 없어 404다(브랜드 표면의 기존 게이트 관용구와 동일).
 *
 * <p>보호 장치가 세 겹이다 - 분당 버스트는 {@link RateLimiter}, 하루 총량은 {@link AiChatQuota},
 * 동시 실행은 전용 풀(brandAiChatExecutor)의 거절이다. 셋 다 429로 수렴하되 code로 구분한다.
 */
@RestController
@RequestMapping("/v1/brand-monitoring/ai")
@ConditionalOnProperty(name = {"monitoring.enabled", "monitoring.brand.ai.enabled"}, havingValue = "true")
public class V1BrandAiChatController {

	/** 동기 응답 상한(설계 §5). */
	private static final int RESPONSE_TIMEOUT_SECONDS = 60;
	/** 분당 질문 수 - 하루 상한과 별개로 연타·자동화를 막는다. */
	private static final int PER_MINUTE_LIMIT = 5;
	/** 대화 이력 상한 - 무상태라 프론트가 무한히 키울 수 있어 서버가 자른다. */
	private static final int MAX_MESSAGES = 20;
	private static final int MAX_CONTENT_LENGTH = 2_000;
	private static final int BUSY_RETRY_AFTER_SECONDS = 10;

	private static final Logger log = LoggerFactory.getLogger(V1BrandAiChatController.class);

	private final BrandAiAgent agent;
	private final AiChatQuota quota;
	private final AiChatLogRepository logRepository;
	private final RateLimiter rateLimiter;
	// 타입을 Executor로 잡는다 - 테스트에서 동기 실행기(Runnable::run)로 갈아끼워 결정론을 얻는다
	private final Executor executor;

	public V1BrandAiChatController(BrandAiAgent agent, AiChatQuota quota,
			AiChatLogRepository logRepository, RateLimiter rateLimiter,
			@Qualifier("brandAiChatExecutor") Executor executor) {
		this.agent = agent;
		this.quota = quota;
		this.logRepository = logRepository;
		this.rateLimiter = rateLimiter;
		this.executor = executor;
	}

	@PostMapping("/chat")
	public ApiResponse<AiChatResponse> chat(@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody(required = false) AiChatRequest request) {
		if (principal == null) {
			throw V1ApiException.unauthorized("UNAUTHORIZED", "로그인이 필요해요.");
		}
		long userId = principal.getUserId();
		List<AiChatMessage> messages = validate(request);
		String question = messages.get(messages.size() - 1).content();

		if (!rateLimiter.tryAcquire("ai-chat:" + userId, PER_MINUTE_LIMIT)) {
			throw V1ApiException.rateLimited();
		}
		quota.requireWithinDailyLimit(userId);

		long startedAt = System.nanoTime();
		BrandAiAgent.AgentOutcome outcome;
		try {
			outcome = runWithTimeout(userId, messages);
		} catch (RuntimeException e) {
			logRepository.insert(new AiChatLogEntry(userId, null, question, null, List.of(), 0, 0,
					elapsedMillis(startedAt), AiChatLogEntry.OUTCOME_LLM_FAILED));
			throw e;
		}

		logRepository.insert(new AiChatLogEntry(userId, outcome.brandId(), question, outcome.answer(),
				outcome.toolCalls(), outcome.promptTokens(), outcome.outputTokens(),
				elapsedMillis(startedAt), outcome.outcome()));
		return ApiResponse.ok(new AiChatResponse(outcome.answer(), outcome.referencedShortCodes()));
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
	private BrandAiAgent.AgentOutcome runWithTimeout(long userId, List<AiChatMessage> messages) {
		CompletableFuture<BrandAiAgent.AgentOutcome> future;
		try {
			future = CompletableFuture.supplyAsync(() -> agent.run(userId, messages), executor);
		} catch (RejectedExecutionException e) {
			throw V1ApiException.rateLimited(BUSY_RETRY_AFTER_SECONDS);
		}
		try {
			return future.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			log.warn("AI 챗 응답 시간 초과({}초) - userId={}", RESPONSE_TIMEOUT_SECONDS, userId);
			throw V1ApiException.badGateway("AI_TIMEOUT", "답변 생성이 너무 오래 걸렸어요. 잠시 후 다시 시도해 주세요.");
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

	private static List<AiChatMessage> validate(AiChatRequest request) {
		if (request == null || request.messages() == null || request.messages().isEmpty()) {
			throw V1ApiException.validation("질문을 입력해 주세요.");
		}
		List<AiChatMessage> messages = request.messages();
		if (messages.size() > MAX_MESSAGES) {
			throw V1ApiException.validation("대화가 너무 길어요. 새 대화로 다시 시작해 주세요.");
		}
		for (AiChatMessage message : messages) {
			if (message == null || message.content() == null || message.content().isBlank()) {
				throw V1ApiException.validation("빈 메시지는 보낼 수 없어요.");
			}
			if (message.content().length() > MAX_CONTENT_LENGTH) {
				throw V1ApiException.validation("메시지가 너무 길어요. 더 짧게 나눠서 물어봐 주세요.");
			}
			if (!AiChatMessage.ROLE_USER.equals(message.role())
					&& !AiChatMessage.ROLE_ASSISTANT.equals(message.role())) {
				throw V1ApiException.validation("메시지 역할이 올바르지 않아요.");
			}
		}
		if (!AiChatMessage.ROLE_USER.equals(messages.get(messages.size() - 1).role())) {
			throw V1ApiException.validation("마지막 메시지는 사용자 질문이어야 해요.");
		}
		return messages;
	}

	private static long elapsedMillis(long startedAtNanos) {
		return (System.nanoTime() - startedAtNanos) / 1_000_000L;
	}
}
