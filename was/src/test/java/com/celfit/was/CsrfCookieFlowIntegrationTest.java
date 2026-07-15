package com.celfit.was;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 실 SPA 쿠키 왕복 회귀 — csrf() 후처리기 없이, 아무 GET에서 받은 XSRF-TOKEN 쿠키의 raw 값을
 * X-XSRF-TOKEN 헤더로 되돌려 보내는 실제 프론트 흐름이 통과해야 한다(SpaCsrfTokenRequestHandler가
 * 없으면 쿠키가 발급되지 않거나 raw 값이 403으로 거부된다 — curl E2E에서 발견된 갭).
 *
 * 별도 클래스·별도 컨텍스트인 이유: spring-security-test의 csrf() 후처리기는 CsrfFilter의
 * 리포지토리를 세션 기반 테스트용으로 컨텍스트 전역에서 바꿔치기한다(WebTestUtils.setCsrfTokenRepository).
 * 같은 컨텍스트에서 csrf()를 쓴 테스트가 하나라도 먼저 돌면 쿠키 발급이 사라져 이 검증이 순서
 * 의존으로 깨진다 — 더미 프로퍼티로 컨텍스트 캐시를 분리해 실제 CookieCsrfTokenRepository를 지킨다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "was.test.context-fork=csrf-cookie-flow")
class CsrfCookieFlowIntegrationTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void XSRF_쿠키는_GET에서_발급되고_raw_값_헤더로_쓰기가_통과한다() throws Exception {
		MvcResult primed = mockMvc.perform(get("/health"))
				.andExpect(status().isOk())
				.andExpect(cookie().exists("XSRF-TOKEN"))
				.andReturn();
		Cookie xsrf = primed.getResponse().getCookie("XSRF-TOKEN");

		mockMvc.perform(post("/api/auth/signup")
						.cookie(xsrf)
						.header("X-XSRF-TOKEN", xsrf.getValue())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"csrf-cookie@example.com","password":"password123"}"""))
				.andExpect(status().isCreated());
	}
}
