package com.celfit.was.v1.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.IntegrationTest;
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
 * 조직 셀프서비스(계획 Task 4, 설계 2026-08-17 §조직 셀프서비스) — /v1/org/** 실 DB·실 필터체인 검증.
 * AdminApiIntegrationTest 관용구(DB 직접 시드 → /v1/auth/login 세션)를 따르되, 여기는 role 게이트가
 * 아니라 org_role 게이트라 일반 USER 계정으로 로그인한다.
 */
@AutoConfigureMockMvc
class OrgControllerIntegrationTest extends IntegrationTest {

	private static final String PASSWORD = "Passw0rd!";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	PasswordEncoder passwordEncoder;

	private long insertUser(String email) {
		jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type, agreed_terms,
				                       agreed_privacy, agreed_age14)
				VALUES (:email, :hash, 'USER', '테스트', 'brand', true, true, true)
				ON CONFLICT (email) DO UPDATE SET role = 'USER'""")
				.param("email", email)
				.param("hash", passwordEncoder.encode(PASSWORD))
				.update();
		return jdbcClient.sql("SELECT id FROM app.users WHERE email = :email")
				.param("email", email)
				.query(Long.class)
				.single();
	}

	private long insertOrganization(String name) {
		return jdbcClient.sql("""
				INSERT INTO app.organizations (name, plan) VALUES (:name, 'ENTERPRISE') RETURNING id
				""")
				.param("name", name)
				.query(Long.class)
				.single();
	}

	private void addMember(long orgId, long userId, String orgRole) {
		jdbcClient.sql("""
				INSERT INTO app.organization_members (org_id, user_id, org_role) VALUES (:orgId, :userId, :orgRole)
				""")
				.param("orgId", orgId)
				.param("userId", userId)
				.param("orgRole", orgRole)
				.update();
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

	@Test
	void ORG_ADMIN은_조회_추가_역할변경_제거를_전부_할_수_있다() throws Exception {
		String adminEmail = "org-self-admin@test.io";
		String newMemberEmail = "org-self-new-member@test.io";
		long adminUserId = insertUser(adminEmail);
		long newMemberUserId = insertUser(newMemberEmail);
		long orgId = insertOrganization("셀프서비스 조직");
		addMember(orgId, adminUserId, "ORG_ADMIN");
		Cookie adminSession = login(adminEmail);

		mockMvc.perform(get("/v1/org").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("셀프서비스 조직"))
				.andExpect(jsonPath("$.data.plan").value("enterprise"))
				.andExpect(jsonPath("$.data.myOrgRole").value("ORG_ADMIN"));

		mockMvc.perform(get("/v1/org/members").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.total").value(1));

		mockMvc.perform(post("/v1/org/members").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"orgRole\":\"MEMBER\"}".formatted(newMemberEmail)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.userId").value(String.valueOf(newMemberUserId)))
				.andExpect(jsonPath("$.data.orgRole").value("MEMBER"));

		mockMvc.perform(patch("/v1/org/members/" + newMemberUserId).with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"orgRole\":\"ORG_ADMIN\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.orgRole").value("ORG_ADMIN"));

		mockMvc.perform(delete("/v1/org/members/" + newMemberUserId).with(csrf()).cookie(adminSession))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/v1/org/members").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.total").value(1));
	}

	@Test
	void MEMBER은_조회는_되지만_관리_행위는_403_NOT_ORG_ADMIN이다() throws Exception {
		String memberEmail = "org-self-member@test.io";
		long memberUserId = insertUser(memberEmail);
		long orgId = insertOrganization("멤버조직");
		addMember(orgId, memberUserId, "MEMBER");
		Cookie memberSession = login(memberEmail);

		mockMvc.perform(get("/v1/org").cookie(memberSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.myOrgRole").value("MEMBER"));

		mockMvc.perform(post("/v1/org/members").with(csrf()).cookie(memberSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"nobody@test.io\",\"orgRole\":\"MEMBER\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("NOT_ORG_ADMIN"));

		mockMvc.perform(delete("/v1/org/members/" + memberUserId).with(csrf()).cookie(memberSession))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("NOT_ORG_ADMIN"));
	}

	@Test
	void 무소속_유저는_404다() throws Exception {
		String email = "org-self-unaffiliated@test.io";
		insertUser(email);
		Cookie session = login(email);

		mockMvc.perform(get("/v1/org").cookie(session))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

		mockMvc.perform(get("/v1/org/members").cookie(session))
				.andExpect(status().isNotFound());
	}

	@Test
	void 타_조직_소속_유저_관리는_404다() throws Exception {
		String adminEmail = "org-self-cross-admin@test.io";
		String otherOrgUserEmail = "org-self-cross-target@test.io";
		long adminUserId = insertUser(adminEmail);
		long otherOrgUserId = insertUser(otherOrgUserEmail);
		long orgA = insertOrganization("조직A");
		long orgB = insertOrganization("조직B");
		addMember(orgA, adminUserId, "ORG_ADMIN");
		addMember(orgB, otherOrgUserId, "MEMBER");
		Cookie adminSession = login(adminEmail);

		mockMvc.perform(patch("/v1/org/members/" + otherOrgUserId).with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"orgRole\":\"ORG_ADMIN\"}"))
				.andExpect(status().isNotFound());

		mockMvc.perform(delete("/v1/org/members/" + otherOrgUserId).with(csrf()).cookie(adminSession))
				.andExpect(status().isNotFound());
	}

	@Test
	void 이미_소속된_유저_추가는_409다() throws Exception {
		String adminEmail = "org-self-dup-admin@test.io";
		String targetEmail = "org-self-dup-target@test.io";
		long adminUserId = insertUser(adminEmail);
		long targetUserId = insertUser(targetEmail);
		long orgA = insertOrganization("조직C");
		long orgB = insertOrganization("조직D");
		addMember(orgA, adminUserId, "ORG_ADMIN");
		addMember(orgB, targetUserId, "MEMBER"); // 이미 다른 조직 소속
		Cookie adminSession = login(adminEmail);

		mockMvc.perform(post("/v1/org/members").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"orgRole\":\"MEMBER\"}".formatted(targetEmail)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("ALREADY_MEMBER"));
	}

	@Test
	void 비인증은_401이다() throws Exception {
		mockMvc.perform(get("/v1/org")).andExpect(status().isUnauthorized());
	}
}
