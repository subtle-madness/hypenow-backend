package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.common.llm.VertexHttpTransport;
import com.celfit.common.llm.VertexTokenProvider;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.brandmonitoring.BrandHashtagPostAssembler;
import com.celfit.was.v1.brandmonitoring.BrandPostAssembler;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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

	private static final Logger log = LoggerFactory.getLogger(BrandAiConfig.class);

	/** 동기 챗의 재시도 횟수 - 85초 예산(한계 재도출 2026-08-31, 스펙 §4) 안에 들어오도록 짧게 잡는다. */
	private static final int CHAT_MAX_ATTEMPTS = 2;
	private static final long CHAT_RETRY_BASE_MILLIS = 2_000L;
	private static final int ERROR_BODY_LOG_LIMIT = 2_000;
	/** Vertex 요청 자체의 타임아웃(C2) - common-llm 기본 120초는 사람이 기다리는 이 경로엔 너무
	 * 길다. 2회 재시도까지 감안해도 45초×2+백오프 ≈ 92초로, 에이전트 벽시계 예산(85초, 한계 재도출
	 * 2026-08-31 - 스펙 §4)이 한 번 더 끊어준다. */
	private static final int CHAT_REQUEST_TIMEOUT_SECONDS = 45;

	/**
	 * Vertex 자격증명·프로젝트가 기동 시점엔 비어 있을 수 있다(I5/M3) - GOOGLE_APPLICATION_CREDENTIALS
	 * 미설정 상태에서 {@link VertexTokenProvider#fromEnv()}를 여기서 바로 부르면 빈 생성 자체가
	 * 예외를 던져 BRAND_AI_ENABLED=true인 채 WAS 전체가 크래시루프에 빠진다(08-12 전력 있는 유형).
	 * 미비하면 예외 대신 error 로그를 남기고, 호출 시점에만 명확히 실패하는 degraded 전송을 등록한다
	 * - 컨트롤러의 기존 catch(RuntimeException) 경로가 이를 그대로 502 AI_UNAVAILABLE로 매핑한다.
	 * AI Studio 폴백은 의도적으로 만들지 않는다(08-18 429 사고 원인 - 되살리지 않는다).
	 */
	@Bean
	public ChatTransport brandAiChatTransport(
			@Value("${monitoring.brand.ai.vertex-project}") String project,
			@Value("${monitoring.brand.ai.vertex-location:global}") String location,
			@Value("${monitoring.brand.ai.model:gemini-3.1-flash-lite}") String model) {
		if (!VertexTokenProvider.credentialsPresent() || project == null || project.isBlank()) {
			log.error("AI 어시스턴트 Vertex 설정 미비 - GOOGLE_APPLICATION_CREDENTIALS={}, vertex-project={} "
							+ "(WAS는 정상 기동하되 브랜드 AI 챗 호출마다 502 AI_UNAVAILABLE을 돌려줍니다)",
					VertexTokenProvider.credentialsPresent() ? "설정됨" : "미설정",
					project == null || project.isBlank() ? "미설정" : "설정됨");
			return degradedTransport();
		}
		VertexHttpTransport http = new VertexHttpTransport(VertexTokenProvider.fromEnv(),
				VertexHttpTransport.DEFAULT_BASE_URL, CHAT_RETRY_BASE_MILLIS,
				CHAT_MAX_ATTEMPTS, ERROR_BODY_LOG_LIMIT, CHAT_REQUEST_TIMEOUT_SECONDS);
		return new VertexChatTransport(http, project, location, model);
	}

	/** 호출 시점에만 실패하는 전송 - 빈 등록 자체는 절대 실패하지 않는다(I5/M3). */
	private static ChatTransport degradedTransport() {
		return body -> {
			throw new IllegalStateException(
					"AI 어시스턴트 Vertex 설정 미비 - GOOGLE_APPLICATION_CREDENTIALS 또는 vertex-project를 확인하세요.");
		};
	}

	/**
	 * thinkingBudget 구성(2026-09-01, 모델 실험③ 차단 요소 해소) - 기본 0(flash 계열, I7: dynamic
	 * thinking이 maxOutputTokens를 잠식하는 것을 막는다). <b>gemini-2.5-pro 계열은 thinking을
	 * 비활성화할 수 없다</b> - thinkingBudget=0을 보내면 Vertex가 "The model does not support
	 * setting thinking_budget to 0" 400을 돌려준다(2026-09-01 실측, BRAND_AI_MODEL=gemini-2.5-pro
	 * 기동 시 전 호출 재현). pro로 실험할 때는 음수(예: -1)를 넘겨 thinkingConfig 자체를 생략한다
	 * (모델 기본 동적 thinking에 맡김).
	 */
	@Bean
	public GeminiChatClient brandAiChatClient(ChatTransport brandAiChatTransport, ObjectMapper objectMapper,
			@Value("${monitoring.brand.ai.thinking-budget:0}") int thinkingBudgetRaw) {
		Integer thinkingBudget = thinkingBudgetRaw < 0 ? null : thinkingBudgetRaw;
		return new GeminiChatClient(brandAiChatTransport, objectMapper, thinkingBudget);
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
		return new BrandAiAgent(brandAiChatClient, brandAiToolbox, objectMapper, Clock.systemUTC());
	}

	/**
	 * 후속 질문 생성기(FE 변경요청서 §3.3, 2026-08-30) - 툴 콜링용 {@link GeminiChatClient} 빈을 그대로
	 * 재사용한다(Vertex 자격증명·모델·재시도 배선이 동일해도 무방 - 구조화 출력 1콜뿐이라 별도 전송
	 * 설정을 새로 둘 이유가 없다).
	 *
	 * <p>F3(2026-08-30 리뷰) - 전용 1스레드 실행기를 배선한다. 기본 ForkJoinPool commonPool을 쓰면
	 * 타임아웃 이후에도(cancel이 인터럽트하지 않는다는 JDK 명세) 최대 45초까지 물려 있는 스레드가
	 * 앱 전역이 공유하는 풀을 잠식한다 - 전용 스레드가 그 대가를 대신 지게 한다.
	 */
	@Bean
	public BrandAiFollowUpGenerator brandAiFollowUpGenerator(GeminiChatClient brandAiChatClient,
			ObjectMapper objectMapper, @Qualifier("brandAiFollowUpExecutor") Executor brandAiFollowUpExecutor) {
		return new BrandAiFollowUpGenerator(brandAiChatClient, objectMapper, brandAiFollowUpExecutor);
	}

	/**
	 * 후속 질문 생성 전용 실행기(F3, 2026-08-30 리뷰) - {@code brandAiChatExecutor}와 같은 이유로 큐 없는
	 * 1스레드: 이미 쓰는 중이면 즉시 거절한다({@link RejectedExecutionException}은
	 * {@link BrandAiFollowUpGenerator#generate}의 catch(RuntimeException)가 빈 배열로 접어 삼킨다 -
	 * 후속 질문은 "기능 저해 금지"라 거절도 실패 관용 경로를 그대로 탄다).
	 */
	@Bean("brandAiFollowUpExecutor")
	public ThreadPoolTaskExecutor brandAiFollowUpExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(0);
		executor.setDaemon(true);
		executor.setThreadNamePrefix("brand-ai-followup-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}

	/**
	 * 챗 전용 실행 풀 - 90초 응답 계약(한계 재도출 2026-08-31, 스펙 §4)을 실제로 지키려면 요청 스레드가 아닌 곳에서 돌리고
	 * 시간 초과를 끊어야 한다. 공용 {@code ConcurrencyLimiter}(permits 4)를 쓰지 않는 이유: 85초짜리
	 * 작업이 그 벌크헤드를 물면 무관한 무거운 엔드포인트까지 함께 굶는다.
	 * 큐 없이 2 스레드 - 넘치면 즉시 거절해 429로 돌려보낸다(대기줄이 길어지면 90초 계약이 먼저 깨진다).
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
