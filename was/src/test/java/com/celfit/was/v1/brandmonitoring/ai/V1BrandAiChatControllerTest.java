package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.account.RateLimiter;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 챗 API 계약 슬라이스 검증(설계 §5·§8) - 에이전트는 mock이고, 여기서 보는 것은 요청 검증·상한
 * 429·로그 적재·응답 형태다. 킬 스위치 404는 컨텍스트 자체가 달라 별도 클래스로 뺀다.
 *
 * <p>실행기는 동기 실행기(Runnable::run)로 갈아끼운다 - 슬라이스 테스트에서 별도 스레드로 넘기면
 * 타이밍 의존 플레이키가 생기고, 여기서 검증하려는 것은 비동기 배선이 아니라 계약이다.
 */
@WebMvcTest(controllers = V1BrandAiChatController.class,
		properties = {"was.cors.allowed-origins=http://localhost:3000", "monitoring.enabled=true",
				"monitoring.brand.ai.enabled=true"})
@Import({V1ExceptionAdvice.class, SecurityConfig.class,
		V1BrandAiChatControllerTest.SyncExecutorConfig.class})
class V1BrandAiChatControllerTest {

	@TestConfiguration
	static class SyncExecutorConfig {

		@Bean("brandAiChatExecutor")
		Executor brandAiChatExecutor() {
			return Runnable::run;
		}
	}

	@Autowired
	MockMvc mockMvc;
	@MockitoBean
	BrandAiAgent agent;
	@MockitoBean
	AiChatQuota quota;
	@MockitoBean
	AiChatLogRepository logRepository;
	@MockitoBean
	RateLimiter rateLimiter;

	@BeforeEach
	void allowRateLimit() {
		// Mockito boolean 기본값이 false라 명시적으로 열어 주지 않으면 모든 테스트가 429가 된다
		given(rateLimiter.tryAcquire(anyString(), anyInt())).willReturn(true);
	}

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	private static String body(String content) {
		return "{\"messages\":[{\"role\":\"user\",\"content\":\"" + content + "\"}]}";
	}

	@Test
	void 답변과_참조_shortCode를_돌려주고_로그를_남긴다() throws Exception {
		given(agent.run(anyLong(), any())).willReturn(new BrandAiAgent.AgentOutcome(
				"3건이에요", List.of("ABC", "DEF"), List.of(), 100, 20, 7L,
				AiChatLogEntry.OUTCOME_OK));

		mockMvc.perform(post("/v1/brand-monitoring/ai/chat").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("알려줘")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.answer").value("3건이에요"))
				.andExpect(jsonPath("$.data.referencedShortCodes[0]").value("ABC"));

		ArgumentCaptor<AiChatLogEntry> captor = ArgumentCaptor.forClass(AiChatLogEntry.class);
		then(logRepository).should(times(1)).insert(captor.capture());
		assertThat(captor.getValue().question()).isEqualTo("알려줘");
		assertThat(captor.getValue().userId()).isEqualTo(7L);
		assertThat(captor.getValue().outcome()).isEqualTo(AiChatLogEntry.OUTCOME_OK);
	}

	@Test
	void 일일_상한_초과는_429다() throws Exception {
		willThrow(new V1ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
				"AI_DAILY_LIMIT_REACHED", "오늘 질문 가능 횟수(30회)를 모두 사용했어요. 내일 다시 시도해 주세요."))
				.given(quota).requireWithinDailyLimit(anyLong());

		mockMvc.perform(post("/v1/brand-monitoring/ai/chat").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("알려줘")))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error.code").value("AI_DAILY_LIMIT_REACHED"));
	}

	@Test
	void 메시지가_비면_400이다() throws Exception {
		mockMvc.perform(post("/v1/brand-monitoring/ai/chat").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"messages\":[]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 마지막_메시지가_사용자_발화가_아니면_400이다() throws Exception {
		mockMvc.perform(post("/v1/brand-monitoring/ai/chat").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"messages\":[{\"role\":\"assistant\",\"content\":\"안녕\"}]}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void LLM_실패는_502_재시도_안내이고_실패도_로그로_남는다() throws Exception {
		given(agent.run(anyLong(), any())).willThrow(new IllegalStateException("Vertex HTTP 500"));

		mockMvc.perform(post("/v1/brand-monitoring/ai/chat").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body("알려줘")))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));

		ArgumentCaptor<AiChatLogEntry> captor = ArgumentCaptor.forClass(AiChatLogEntry.class);
		then(logRepository).should(times(1)).insert(captor.capture());
		assertThat(captor.getValue().outcome()).isEqualTo(AiChatLogEntry.OUTCOME_LLM_FAILED);
		assertThat(captor.getValue().answer()).isNull();
	}
}
