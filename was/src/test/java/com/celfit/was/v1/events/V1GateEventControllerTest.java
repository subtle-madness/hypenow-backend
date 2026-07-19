package com.celfit.was.v1.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.account.RateLimiter;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 게이트 이벤트(스펙 6.19) — fire-and-forget 204·인증 Optional·eventType 누락 스킵·레이트리밋 429. */
@WebMvcTest(controllers = V1GateEventController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V1GateEventControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	GateEventRepository repository;

	@MockitoBean
	RateLimiter rateLimiter;

	@BeforeEach
	void allowRate() {
		// 기본은 통과 — 레이트리밋 테스트에서만 false로 덮어쓴다(boolean mock 기본값 false 방지).
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
	}

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	@Test
	void 로그인_이벤트는_204이고_userId와_payload를_기록한다() throws Exception {
		mockMvc.perform(post("/v1/events/gate").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"eventType":"save","payload":{"contentId":"c1","locked":true}}"""))
				.andExpect(status().isNoContent());

		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		then(repository).should().insert(eq(7L), eq("save"), payload.capture());
		assertThat(payload.getValue()).isEqualTo("""
				{"contentId":"c1","locked":true}""");
	}

	@Test
	void 익명_이벤트는_204이고_userId가_null이다() throws Exception {
		mockMvc.perform(post("/v1/events/gate").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"eventType":"gate_view","payload":{"section":"hero"}}"""))
				.andExpect(status().isNoContent());

		then(repository).should().insert(isNull(), eq("gate_view"), anyString());
	}

	@Test
	void CSRF_토큰_없이도_익명_이벤트는_204다() throws Exception {
		// 게이트 이벤트는 CSRF 면제(SecurityConfig) — 익명 첫 방문자는 XSRF-TOKEN 쿠키를 미리 받을 수
		// 없어 면제 없이는 스펙 6.19의 "익명 기록"이 유실된다. .with(csrf()) 없이도 통과해야 정상.
		mockMvc.perform(post("/v1/events/gate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"eventType":"gate_view","payload":{"section":"hero"}}"""))
				.andExpect(status().isNoContent());

		then(repository).should().insert(isNull(), eq("gate_view"), anyString());
	}

	@Test
	void eventType_누락은_기록을_스킵하고_204다() throws Exception {
		mockMvc.perform(post("/v1/events/gate").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"payload":{"contentId":"c1"}}"""))
				.andExpect(status().isNoContent());

		// 4xx 남발 금지(스펙 6.19) — eventType이 없으면 기록만 건너뛰고 정상 204.
		then(repository).should(never()).insert(any(), anyString(), any());
	}

	@Test
	void 공백_eventType도_기록을_스킵하고_204다() throws Exception {
		mockMvc.perform(post("/v1/events/gate").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"eventType":"   ","payload":{"contentId":"c1"}}"""))
				.andExpect(status().isNoContent());

		then(repository).should(never()).insert(any(), anyString(), any());
	}

	@Test
	void 레이트리밋_초과는_429_RATE_LIMITED이고_기록하지_않는다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(false);

		mockMvc.perform(post("/v1/events/gate").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"eventType":"save","payload":{"contentId":"c1"}}"""))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
				.andExpect(jsonPath("$.error.message").value("요청이 너무 잦아요. 잠시 후 다시 시도해 주세요."));

		then(repository).should(never()).insert(any(), anyString(), any());
	}

	@Test
	void payload가_없는_이벤트는_204이고_payloadJson은_null이다() throws Exception {
		mockMvc.perform(post("/v1/events/gate").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"eventType":"unlock_click"}"""))
				.andExpect(status().isNoContent());

		then(repository).should().insert(eq(7L), eq("unlock_click"), isNull());
	}
}
