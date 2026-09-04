package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.crypto.FieldCipher;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 유저별 기능 플래그(app.users.feature_overrides) — GET /v1/me·GET /v1/admin/users/{id} 노출과
 * PUT /v1/admin/users/{id}/features 전체 교체·검증·감사 기록을 실 DB·실 필터체인으로 검증한다.
 * 계정은 DB 직접 시드 후 /v1/auth/login으로 세션 획득(AdminApiIntegrationTest 관용구).
 */
@AutoConfigureMockMvc
class UserFeatureOverridesIntegrationTest extends IntegrationTest {

	private static final String PASSWORD = "Passw0rd!";
	private static final String ADMIN_EMAIL = "flags-admin@test.io";
	private static final String TARGET_EMAIL = "flags-target@test.io";
	private static final String OTHER_EMAIL = "flags-other@test.io";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	FieldCipher fieldCipher;

	private long adminId;
	private long targetId;

	@BeforeEach
	void seedUsers() {
		adminId = insertUser(ADMIN_EMAIL, "ADMIN");
		targetId = insertUser(TARGET_EMAIL, "USER");
		insertUser(OTHER_EMAIL, "USER");
		// 컨테이너는 JVM 공유 — 앞선 테스트가 남긴 설정을 기본값으로 되돌린다.
		jdbcClient.sql("UPDATE app.users SET feature_overrides = '{}'::jsonb WHERE id IN (:ids)")
				.param("ids", List.of(adminId, targetId))
				.update();
		jdbcClient.sql("DELETE FROM app.admin_audit_logs WHERE admin_id = :adminId")
				.param("adminId", adminId)
				.update();
	}

	private long insertUser(String email, String role) {
		jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type, agreed_terms,
				                       agreed_privacy, agreed_age14)
				VALUES (:email, :hash, :role, '테스트', 'brand', true, true, true)
				ON CONFLICT (email) DO UPDATE SET role = :role""")
				.param("email", email)
				.param("hash", passwordEncoder.encode(PASSWORD))
				.param("role", role)
				.update();
		PiiTestSeed.backfill(jdbcClient, fieldCipher);
		return jdbcClient.sql("SELECT id FROM app.users WHERE email = :email")
				.param("email", email)
				.query(Long.class)
				.single();
	}

	private Cookie login(String email) throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
				.andExpect(status().isOk())
				.andReturn();
		Cookie session = result.getResponse().getCookie("hypenow-session");
		assertThat(session).isNotNull();
		return session;
	}

	private ResultActions putFeatures(Cookie session, long userId, String body) throws Exception {
		return mockMvc.perform(put("/v1/admin/users/" + userId + "/features").with(csrf()).cookie(session)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	@Test
	void 기본값은_빈_객체이고_me와_어드민_상세_모두_null이_아니다() throws Exception {
		Cookie admin = login(ADMIN_EMAIL);
		Cookie target = login(TARGET_EMAIL);

		mockMvc.perform(get("/v1/me").cookie(target))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.featureOverrides").isMap())
				.andExpect(jsonPath("$.data.featureOverrides").isEmpty());

		mockMvc.perform(get("/v1/admin/users/" + targetId).cookie(admin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.featureOverrides").isMap())
				.andExpect(jsonPath("$.data.featureOverrides").isEmpty());

		mockMvc.perform(get("/v1/admin/users").param("query", TARGET_EMAIL).cookie(admin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].featureOverrides").isMap())
				.andExpect(jsonPath("$.data[0].featureOverrides").isEmpty());
	}

	/** 매트릭스 화면이 목록 1회로 끝나야 한다(상세 N회 금지) — 목록 행이 상세와 같은 값을 든다. */
	@Test
	void 목록_행의_featureOverrides는_상세와_같은_값이다() throws Exception {
		Cookie admin = login(ADMIN_EMAIL);
		putFeatures(admin, targetId,
				"{\"overrides\":{\"influencer_search\":[\"beauty\",\"fnb\"],\"content_ranking\":true}}")
				.andExpect(status().isOk());

		mockMvc.perform(get("/v1/admin/users").param("query", TARGET_EMAIL).cookie(admin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].email").value(TARGET_EMAIL))
				.andExpect(jsonPath("$.data[0].featureOverrides.influencer_search[0]").value("beauty"))
				.andExpect(jsonPath("$.data[0].featureOverrides.influencer_search[1]").value("fnb"))
				.andExpect(jsonPath("$.data[0].featureOverrides.content_ranking").value(true));
	}

	@Test
	void 어드민_PUT은_저장값을_그대로_돌려주고_어드민_상세에_반영된다() throws Exception {
		Cookie admin = login(ADMIN_EMAIL);

		putFeatures(admin, targetId,
				"{\"overrides\":{\"influencer_search\":[\"beauty\",\"fnb\"],\"content_ranking\":true}}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.overrides.influencer_search[0]").value("beauty"))
				.andExpect(jsonPath("$.data.overrides.influencer_search[1]").value("fnb"))
				.andExpect(jsonPath("$.data.overrides.content_ranking").value(true));

		mockMvc.perform(get("/v1/admin/users/" + targetId).cookie(admin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.featureOverrides.influencer_search[0]").value("beauty"))
				.andExpect(jsonPath("$.data.featureOverrides.content_ranking").value(true));
	}

	/** 반영 시점 계약 — /v1/me는 매 요청 DB를 읽으므로 저장 전에 만든 세션 그대로 즉시 반영된다. */
	@Test
	void 저장은_대상_유저의_기존_세션_me에_즉시_반영된다() throws Exception {
		Cookie admin = login(ADMIN_EMAIL);
		Cookie target = login(TARGET_EMAIL);   // 저장 이전에 발급된 세션

		mockMvc.perform(get("/v1/me").cookie(target))
				.andExpect(jsonPath("$.data.featureOverrides").isEmpty());

		putFeatures(admin, targetId, "{\"overrides\":{\"influencer_search\":[\"beauty\"]}}")
				.andExpect(status().isOk());

		mockMvc.perform(get("/v1/me").cookie(target))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.featureOverrides.influencer_search[0]").value("beauty"));
	}

	@Test
	void PUT은_병합이_아니라_전체_교체다() throws Exception {
		Cookie admin = login(ADMIN_EMAIL);

		putFeatures(admin, targetId, "{\"overrides\":{\"a\":true,\"b\":[\"x\"]}}")
				.andExpect(status().isOk());
		putFeatures(admin, targetId, "{\"overrides\":{\"b\":[\"y\"]}}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.overrides.a").doesNotExist())
				.andExpect(jsonPath("$.data.overrides.b[0]").value("y"));

		putFeatures(admin, targetId, "{\"overrides\":{}}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.overrides").isEmpty());
	}

	@Test
	void 값이_boolean이나_문자열배열이_아니면_400_VALIDATION_FAILED다() throws Exception {
		Cookie admin = login(ADMIN_EMAIL);

		for (String bad : List.of(
				"{\"overrides\":{\"a\":1}}",
				"{\"overrides\":{\"a\":\"beauty\"}}",
				"{\"overrides\":{\"a\":null}}",
				"{\"overrides\":{\"a\":{\"b\":true}}}",
				"{\"overrides\":{\"a\":[\"x\",1]}}",
				"{\"overrides\":{\"a\":[[\"x\"]]}}",
				"{\"overrides\":[]}")) {
			putFeatures(admin, targetId, bad)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}

		// 실패한 요청은 저장도 감사 기록도 남기지 않는다.
		assertThat(storedOverrides(targetId)).isEqualTo("{}");
		assertThat(auditCount(adminId, targetId)).isZero();
	}

	@Test
	void overrides_키가_없으면_400_VALIDATION_FAILED다() throws Exception {
		Cookie admin = login(ADMIN_EMAIL);

		putFeatures(admin, targetId, "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void body가_8KB를_넘으면_400_VALIDATION_FAILED다() throws Exception {
		Cookie admin = login(ADMIN_EMAIL);
		StringBuilder body = new StringBuilder("{\"overrides\":{\"a\":[");
		for (int i = 0; i < 500; i++) {
			body.append(i == 0 ? "" : ",").append("\"").append("x".repeat(16)).append("\"");
		}
		body.append("]}}");
		assertThat(body.length()).isGreaterThan(8 * 1024);

		putFeatures(admin, targetId, body.toString())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		assertThat(storedOverrides(targetId)).isEqualTo("{}");
	}

	@Test
	void 부재_유저는_404_NOT_FOUND다() throws Exception {
		Cookie admin = login(ADMIN_EMAIL);

		putFeatures(admin, 999_999_999L, "{\"overrides\":{\"a\":true}}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 비어드민은_403이고_미인증은_401이다() throws Exception {
		Cookie other = login(OTHER_EMAIL);

		putFeatures(other, targetId, "{\"overrides\":{\"a\":true}}")
				.andExpect(status().isForbidden());

		mockMvc.perform(put("/v1/admin/users/" + targetId + "/features").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"overrides\":{\"a\":true}}"))
				.andExpect(status().isUnauthorized());

		assertThat(storedOverrides(targetId)).isEqualTo("{}");
	}

	@Test
	void 성공한_PUT은_어드민_대상_시각을_감사로그에_남긴다() throws Exception {
		Cookie admin = login(ADMIN_EMAIL);
		OffsetDateTime before = OffsetDateTime.now().minusMinutes(1);

		putFeatures(admin, targetId, "{\"overrides\":{\"a\":true}}")
				.andExpect(status().isOk());

		String path = jdbcClient.sql("""
				SELECT path FROM app.admin_audit_logs
				WHERE admin_id = :adminId AND target_user_id = :targetId AND at > :before
				ORDER BY at DESC LIMIT 1""")
				.param("adminId", adminId)
				.param("targetId", targetId)
				.param("before", before)
				.query(String.class)
				.single();
		assertThat(path).isEqualTo("/v1/admin/users/" + targetId + "/features");
	}

	private String storedOverrides(long userId) {
		return jdbcClient.sql("SELECT feature_overrides::text FROM app.users WHERE id = :id")
				.param("id", userId)
				.query(String.class)
				.single();
	}

	private long auditCount(long adminId, long targetUserId) {
		return jdbcClient.sql("""
				SELECT count(*) FROM app.admin_audit_logs
				WHERE admin_id = :adminId AND target_user_id = :targetId""")
				.param("adminId", adminId)
				.param("targetId", targetUserId)
				.query(Long.class)
				.single();
	}
}
