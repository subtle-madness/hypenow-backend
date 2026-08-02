package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 익명 세션 누적 회귀 방지(08-02 실측: app.spring_session 1,509행 중 97%가 SAVED_REQUEST 단일
 * attribute만 가진 고아 세션). 원인은 @Order(2) securityFilterChain에 기본 HttpSessionRequestCache가
 * 붙어 있어, 미인증 요청이 anyRequest().authenticated()에 걸릴 때마다
 * ExceptionTranslationFilter.sendStartAuthentication()이 requestCache.saveRequest()를 호출하고
 * 이게 request.getSession(true)로 세션을 새로 만드는 것 — 폼 로그인 리다이렉트 흐름이 없어
 * (formLogin disabled, JSON 로그인) 저장된 SavedRequest를 아무도 소비하지 않는 죽은 기능이었다.
 *
 * <p>@DirtiesContext(BEFORE_CLASS) — 이 테스트는 Set-Cookie 헤더 부재를 엄격히 단언하는데,
 * 다른 다수 테스트와 컨텍스트를 공유하면(같은 @AutoConfigureMockMvc+IntegrationTest 시그니처라
 * 캐시가 재사용한다) 전체 스위트 특정 조합에서 원인 불명의 세션 쿠키가 관측됐다(08-02 조사:
 * requestCache는 리플렉션으로 NullRequestCache 확인, getSession() 호출도 스파이 필터로 0회
 * 확인 — 이 fix의 대상 경로는 아니었다). 원인 규명 대신 전용 컨텍스트로 격리해 결정론적으로 만든다.
 */
@AutoConfigureMockMvc
@DirtiesContext(classMode = ClassMode.BEFORE_CLASS)
class AnonymousSessionLeakTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void 미인증_보호_엔드포인트_호출은_세션을_만들지_않는다() throws Exception {
		MvcResult result = mockMvc.perform(get("/v1/me"))
				.andExpect(status().isUnauthorized())
				.andReturn();

		assertThat(result.getRequest().getSession(false)).isNull();
		assertThat(result.getResponse().getHeaders("Set-Cookie"))
				.noneMatch(header -> header.startsWith("hypenow-session="));
	}

}
