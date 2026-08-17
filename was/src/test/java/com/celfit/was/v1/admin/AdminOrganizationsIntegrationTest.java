package com.celfit.was.v1.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 어드민 조직 관리 CRUD(계획 Task 3, 설계 2026-08-17 §어드민 API) 실 DB·실 필터체인 검증 —
 * AdminApiIntegrationTest·AdminNoticesIntegrationTest 관용구(DB 직접 시드 → /v1/auth/login 세션 획득)를
 * 그대로 따른다. FeatureKey enum이 빈 상태라 오버라이드 happy path는 불가 — "없는 featureKey 400"만
 * 여기서 확인하고, 저장 로직 자체는 OrganizationRepository 단위 IT가 임의 문자열 키로 검증한다.
 */
@AutoConfigureMockMvc
class AdminOrganizationsIntegrationTest extends IntegrationTest {

	private static final String PASSWORD = "Passw0rd!";
	private static final String ADMIN_EMAIL = "orgs-admin@test.io";
	private static final String USER_EMAIL = "orgs-user@test.io";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	PasswordEncoder passwordEncoder;

	private Cookie adminSession;
	private long memberUserId;

	@BeforeEach
	void setUp() throws Exception {
		jdbcClient.sql("TRUNCATE app.organization_feature_overrides").update();
		jdbcClient.sql("TRUNCATE app.organization_members").update();
		jdbcClient.sql("TRUNCATE app.organizations RESTART IDENTITY CASCADE").update();
		insertUser(ADMIN_EMAIL, "ADMIN");
		memberUserId = insertUser(USER_EMAIL, "USER");
		adminSession = login(ADMIN_EMAIL);
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

	private long createOrg(String name, String plan) throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/admin/organizations").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"%s","plan":"%s","contractStart":"2026-01-01","contractEnd":"2026-12-31"}
								""".formatted(name, plan)))
				.andExpect(status().isCreated())
				.andReturn();
		return Long.parseLong(JsonPath.read(result.getResponse().getContentAsString(), "$.data.id").toString());
	}

	@Test
	void 생성_멤버_배정_역할변경_해지_전체_해피패스() throws Exception {
		long orgId = createOrg("하입나우 파트너스", "ENTERPRISE");

		mockMvc.perform(get("/v1/admin/organizations/" + orgId).cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.plan").value("enterprise"))
				.andExpect(jsonPath("$.data.contractStart").value("2026-01-01"))
				.andExpect(jsonPath("$.data.members.length()").value(0))
				.andExpect(jsonPath("$.data.overrides.length()").value(0));

		mockMvc.perform(get("/v1/admin/organizations").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.total").value(1));

		mockMvc.perform(post("/v1/admin/organizations/" + orgId + "/members").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":%d,\"orgRole\":\"MEMBER\"}".formatted(memberUserId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.userId").value(String.valueOf(memberUserId)))
				.andExpect(jsonPath("$.data.email").value(USER_EMAIL))
				.andExpect(jsonPath("$.data.orgRole").value("MEMBER"));

		mockMvc.perform(patch("/v1/admin/organizations/" + orgId + "/members/" + memberUserId)
						.with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"orgRole\":\"ORG_ADMIN\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.orgRole").value("ORG_ADMIN"));

		mockMvc.perform(get("/v1/admin/organizations/" + orgId).cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.members.length()").value(1))
				.andExpect(jsonPath("$.data.members[0].orgRole").value("ORG_ADMIN"));

		mockMvc.perform(patch("/v1/admin/organizations/" + orgId).with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"plan\":\"FREE\",\"contractEnd\":null}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.plan").value("free"))
				.andExpect(jsonPath("$.data.contractEnd").doesNotExist());

		mockMvc.perform(delete("/v1/admin/organizations/" + orgId + "/members/" + memberUserId)
						.with(csrf()).cookie(adminSession))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/v1/admin/organizations/" + orgId).cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.members.length()").value(0));
	}

	@Test
	void 이미_타_조직_소속인_유저_추가는_409다() throws Exception {
		long org1 = createOrg("조직1", "ENTERPRISE");
		long org2 = createOrg("조직2", "ENTERPRISE");

		mockMvc.perform(post("/v1/admin/organizations/" + org1 + "/members").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":%d,\"orgRole\":\"MEMBER\"}".formatted(memberUserId)))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/v1/admin/organizations/" + org2 + "/members").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":%d,\"orgRole\":\"MEMBER\"}".formatted(memberUserId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("ALREADY_MEMBER"));
	}

	@Test
	void 없는_유저_추가는_404다() throws Exception {
		long orgId = createOrg("조직", "ENTERPRISE");

		mockMvc.perform(post("/v1/admin/organizations/" + orgId + "/members").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":999999999,\"orgRole\":\"MEMBER\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	@Test
	void 없는_featureKey_오버라이드는_400이다() throws Exception {
		long orgId = createOrg("조직", "ENTERPRISE");

		mockMvc.perform(put("/v1/admin/organizations/" + orgId + "/overrides/NOT_A_REAL_KEY")
						.with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"enabled\":true}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("UNKNOWN_FEATURE_KEY"));
	}

	@Test
	void 일반_유저는_403이고_비로그인은_401이다() throws Exception {
		Cookie userSession = login(USER_EMAIL);

		mockMvc.perform(post("/v1/admin/organizations").with(csrf()).cookie(userSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"조직\",\"plan\":\"ENTERPRISE\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		mockMvc.perform(post("/v1/admin/organizations").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"조직\",\"plan\":\"ENTERPRISE\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 부재_조직_조회와_비숫자_id는_404다() throws Exception {
		mockMvc.perform(get("/v1/admin/organizations/999999999").cookie(adminSession))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

		mockMvc.perform(get("/v1/admin/organizations/not-a-number").cookie(adminSession))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}
}
