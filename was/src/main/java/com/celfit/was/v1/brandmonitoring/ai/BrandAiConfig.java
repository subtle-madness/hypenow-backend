package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.common.llm.VertexHttpTransport;
import com.celfit.common.llm.VertexTokenProvider;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.brandmonitoring.BrandHashtagPostAssembler;
import com.celfit.was.v1.brandmonitoring.BrandPostAssembler;
import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 어시스턴트 배선(설계 §3·§7). monitoring.enabled와 킬 스위치가 <b>둘 다</b> true일 때만 뜬다 -
 * {@link BrandReadRepository} 자체가 monitoring.enabled 조건부 빈이라 하나만 켜면 배선이 깨진다.
 *
 * <p>재시도를 common-llm 기본값(6회·15초 기저)보다 크게 줄인다(2회·2초): 야간 배치와 달리 이 경로는
 * 사람이 기다리는 동기 요청이라 오래 매달리는 것보다 빨리 실패해 재시도를 안내하는 편이 낫다.
 */
@Configuration
@ConditionalOnProperty(name = {"monitoring.enabled", "monitoring.brand.ai.enabled"}, havingValue = "true")
public class BrandAiConfig {

	/** 동기 챗의 재시도 횟수 - 60초 응답 계약(설계 §5) 안에 들어오도록 짧게 잡는다. */
	private static final int CHAT_MAX_ATTEMPTS = 2;
	private static final long CHAT_RETRY_BASE_MILLIS = 2_000L;
	private static final int ERROR_BODY_LOG_LIMIT = 2_000;

	@Bean
	public ChatTransport brandAiChatTransport(
			@Value("${monitoring.brand.ai.vertex-project}") String project,
			@Value("${monitoring.brand.ai.vertex-location:global}") String location,
			@Value("${monitoring.brand.ai.model:gemini-2.5-flash}") String model) {
		VertexHttpTransport http = new VertexHttpTransport(VertexTokenProvider.fromEnv(),
				VertexHttpTransport.DEFAULT_BASE_URL, CHAT_RETRY_BASE_MILLIS,
				CHAT_MAX_ATTEMPTS, ERROR_BODY_LOG_LIMIT);
		return new VertexChatTransport(http, project, location, model);
	}

	@Bean
	public GeminiChatClient brandAiChatClient(ChatTransport brandAiChatTransport, ObjectMapper objectMapper) {
		return new GeminiChatClient(brandAiChatTransport, objectMapper);
	}

	@Bean
	public BrandAiToolbox brandAiToolbox(BrandLinkRepository linkRepository,
			BrandReadRepository brandReadRepository, BrandPostAssembler postAssembler,
			BrandHashtagPostAssembler hashtagPostAssembler, ObjectMapper objectMapper) {
		// 표시 표면(FE)과 같은 격리 경로 위에 재배치(2026-08-27 리뷰 C1/I2/I3/I4/I9) - 유저별 가시성
		// 필터·표시 창 검사·경쟁사 광고 판정 억제는 BrandPostAssembler·BrandHashtagPostAssembler가
		// 이미 강제하므로(광고 판정 노출 토글도 그쪽 배선에 있다) 여기서 따로 흉내내지 않는다.
		// Clock은 빈이 아니라 직접 만든다 - was에 Clock 빈이 없고(생성자 직접 주입 관용구), 전역 빈을
		// 새로 등록하면 자기 fixed Clock을 띄우는 기존 통합 테스트들과 충돌한다.
		return new BrandAiToolbox(linkRepository, brandReadRepository, postAssembler, hashtagPostAssembler,
				objectMapper, Clock.systemUTC());
	}

	@Bean
	public AiChatQuota aiChatQuota(AiChatLogRepository logRepository,
			AppSettingRepository settingRepository) {
		return new AiChatQuota(logRepository, settingRepository, Clock.systemUTC());
	}

	@Bean
	public BrandAiAgent brandAiAgent(GeminiChatClient brandAiChatClient, BrandAiToolbox brandAiToolbox,
			ObjectMapper objectMapper) {
		return new BrandAiAgent(brandAiChatClient, brandAiToolbox, objectMapper);
	}

	/**
	 * 챗 전용 실행 풀 - 60초 응답 계약(설계 §5)을 실제로 지키려면 요청 스레드가 아닌 곳에서 돌리고
	 * 시간 초과를 끊어야 한다. 공용 {@code ConcurrencyLimiter}(permits 4)를 쓰지 않는 이유: 60초짜리
	 * 작업이 그 벌크헤드를 물면 무관한 무거운 엔드포인트까지 함께 굶는다.
	 * 큐 없이 2 스레드 - 넘치면 즉시 거절해 429로 돌려보낸다(대기줄이 길어지면 60초 계약이 먼저 깨진다).
	 */
	@Bean("brandAiChatExecutor")
	public ThreadPoolTaskExecutor brandAiChatExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(0);
		executor.setThreadNamePrefix("brand-ai-chat-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}
}
