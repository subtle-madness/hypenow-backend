package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Spring Session JDBC 영속화 계약 — 로그인하면 app.spring_session에 행이 생기고(principal_name=이메일,
 * 30일 슬라이딩), Set-Cookie가 스펙 4절(hypenow-session·SameSite=Lax·Max-Age)을 지키는지 실측한다.
 * Boot 프로퍼티(spring.session.timeout / server.servlet.session.cookie.*)가 실제
 * CookieSerializer·세션 저장소에 반영되는지가 검증 대상이라 헤더·DB를 직접 본다.
 */
@AutoConfigureMockMvc
class SessionPersistenceIntegrationTest extends IntegrationTest {

	private static final String EMAIL = "session-persist@example.com";
	private static final String CHROME_MAC_UA =
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
					+ "Chrome/126.0.0.0 Safari/537.36";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Test
	void 로그인하면_세션이_DB에_영속화되고_쿠키가_스펙_계약을_지킨다() throws Exception {
		// 로그인 월(07-17) — 레거시 /api/auth는 잠겨 v1으로 가입(코드 개통 선행). 가입 자동 로그인
		// 세션이 하나 더 생기므로 아래 DB 조회는 이메일이 아니라 로그인 쿠키의 session_id로 짚는다.
		V1AuthTestSteps.enableSignupCode(jdbcClient);
		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(V1AuthTestSteps.signupBody(EMAIL)))
				.andExpect(status().isCreated());

		MvcResult login = mockMvc.perform(post("/v1/auth/login").with(csrf())
						.header("User-Agent", CHROME_MAC_UA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"%s","password":"%s"}""".formatted(EMAIL, V1AuthTestSteps.PASSWORD)))
				.andExpect(status().isOk())
				.andReturn();

		// ① Set-Cookie 실측 — 이름·HttpOnly·Path·SameSite·Max-Age(30일=2592000초)
		List<String> setCookies = login.getResponse().getHeaders("Set-Cookie");
		String sessionSetCookie = setCookies.stream()
				.filter(h -> h.startsWith("hypenow-session="))
				.findFirst()
				.orElseThrow(() -> new AssertionError("hypenow-session Set-Cookie가 없다 — 실제: " + setCookies));
		assertThat(sessionSetCookie)
				.contains("HttpOnly")
				.contains("Path=/")
				.contains("SameSite=Lax")
				.contains("Max-Age=2592000");

		// ② DB 영속화 — 로그인 쿠키의 session_id 행, principal_name=이메일, 비활동 만료 30일(2592000초)
		String cookieValueForDb = sessionSetCookie.substring("hypenow-session=".length(), sessionSetCookie.indexOf(';'));
		String sessionId = new String(java.util.Base64.getDecoder().decode(cookieValueForDb),
				java.nio.charset.StandardCharsets.US_ASCII);
		Map<String, Object> row = jdbcClient.sql("""
						select primary_id, principal_name, max_inactive_interval from app.spring_session
						where session_id = :sessionId""")
				.param("sessionId", sessionId)
				.query()
				.singleRow();
		assertThat(row.get("principal_name")).isEqualTo(EMAIL);
		assertThat(row.get("max_inactive_interval")).isEqualTo(2592000);

		// ③ 로그인 시점 UA attribute가 세션과 함께 저장된다 (스펙 6.14 세션 목록 재료)
		List<String> attributeNames = jdbcClient.sql("""
						select attribute_name from app.spring_session_attributes
						where session_primary_id = :primaryId""")
				.param("primaryId", row.get("primary_id"))
				.query(String.class)
				.list();
		assertThat(attributeNames).contains("SPRING_SECURITY_CONTEXT", "session.browser", "session.os");

		// ④ 비밀번호 해시가 세션 직렬화 바이트에 없다 — CredentialsContainer.eraseCredentials()가
		// 인증 성공 직후 지웠어야 한다. BCrypt 해시는 ASCII라 포함됐다면 바이트에 그대로 보인다.
		byte[] contextBytes = jdbcClient.sql("""
						select attribute_bytes from app.spring_session_attributes
						where session_primary_id = :primaryId and attribute_name = 'SPRING_SECURITY_CONTEXT'""")
				.param("primaryId", row.get("primary_id"))
				.query(byte[].class)
				.single();
		String passwordHash = jdbcClient.sql("select password_hash from app.users where email = :email")
				.param("email", EMAIL)
				.query(String.class)
				.single();
		String contextText = new String(contextBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
		assertThat(contextText).contains(EMAIL); // 읽은 바이트가 진짜 직렬화 본문인지 양성 대조
		assertThat(contextText).doesNotContain(passwordHash);

		// ⑤ 쿠키 왕복 — DB에 실린 세션으로 /v1/me 인증이 성립한다(재시작 생존과 같은 경로)
		mockMvc.perform(get("/v1/me").cookie(new Cookie("hypenow-session", cookieValueForDb)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value(EMAIL));
	}
}
