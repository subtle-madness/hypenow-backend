package com.celfit.was;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * signup/login/logout/me의 세션 쿠키·CSRF·401 계약을 실 DB(Testcontainers) + 실 필터체인으로 검증한다.
 * MockMvc는 spring-security-test 덕분에 springSecurityFilterChain을 그대로 통과한다.
 */
@AutoConfigureMockMvc
class AuthFlowIntegrationTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void signup은_201_중복은_409_형식오류는_400이다() throws Exception {
		mockMvc.perform(post("/api/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"signup1@example.com","password":"password123"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value("signup1@example.com"));

		mockMvc.perform(post("/api/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"SIGNUP1@example.com","password":"password123"}"""))
				.andExpect(status().isConflict());

		mockMvc.perform(post("/api/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"bad-email","password":"password123"}"""))
				.andExpect(status().isBadRequest());

		mockMvc.perform(post("/api/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"signup2@example.com","password":"short"}"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void login은_200_세션을_성립시키고_잘못된_비밀번호는_401이다() throws Exception {
		mockMvc.perform(post("/api/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"login1@example.com","password":"password123"}"""))
				.andExpect(status().isCreated());

		MvcResult result = mockMvc.perform(post("/api/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"login1@example.com","password":"password123"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("login1@example.com"))
				.andReturn();
		assertNotNull(result.getRequest().getSession(false));

		mockMvc.perform(post("/api/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"login1@example.com","password":"wrong-password"}"""))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void me는_로그인_후_200이고_미로그인이면_401이다() throws Exception {
		mockMvc.perform(post("/api/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"me1@example.com","password":"password123"}"""))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/api/me"))
				.andExpect(status().isUnauthorized());

		MvcResult loginResult = mockMvc.perform(post("/api/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"me1@example.com","password":"password123"}"""))
				.andExpect(status().isOk())
				.andReturn();

		mockMvc.perform(get("/api/me").session((MockHttpSession) loginResult.getRequest().getSession()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("me1@example.com"));
	}

	@Test
	void logout_후에는_me가_401이다() throws Exception {
		mockMvc.perform(post("/api/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"logout1@example.com","password":"password123"}"""))
				.andExpect(status().isCreated());

		MvcResult loginResult = mockMvc.perform(post("/api/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"logout1@example.com","password":"password123"}"""))
				.andExpect(status().isOk())
				.andReturn();
		MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();

		mockMvc.perform(post("/api/auth/logout").with(csrf()).session(session))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/me").session(session))
				.andExpect(status().isUnauthorized());

		// 미로그인 상태에서 logout을 호출해도 204(멱등)
		mockMvc.perform(post("/api/auth/logout").with(csrf()))
				.andExpect(status().isNoContent());
	}

	@Test
	void CSRF_토큰_없는_쓰기는_403이다() throws Exception {
		mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"csrf1@example.com","password":"password123"}"""))
				.andExpect(status().isForbidden());
	}
}
