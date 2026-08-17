package com.celfit.was.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link EntitlementService#entitlementsFor} 실 DB 검증(계획 Task 1, 설계 2026-08-17 §판정 로직) —
 * 무소속·계약 만료 폴백·활성 계약 유지를 조직/멤버십 테이블 직접 시드로 확인한다.
 * 오버라이드 합성(compose)은 FeatureKey가 빈 enum이라 여기서 실 오버라이드 행으로 검증할 수 없다
 * (EntitlementServiceComposeTest가 순수 함수 단위로 커버).
 */
class EntitlementServiceIntegrationTest extends IntegrationTest {

	@Autowired
	EntitlementService entitlementService;

	@Autowired
	JdbcClient jdbcClient;

	private long userId;

	@BeforeEach
	void seedUser() {
		userId = insertUser("entitlement-" + System.nanoTime() + "@test.io");
	}

	private long insertUser(String email) {
		jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type, agreed_terms,
				                       agreed_privacy, agreed_age14)
				VALUES (:email, 'x', 'USER', '테스트', 'brand', true, true, true)
				""")
				.param("email", email)
				.update();
		return jdbcClient.sql("SELECT id FROM app.users WHERE email = :email")
				.param("email", email)
				.query(Long.class)
				.single();
	}

	private long insertOrganization(String plan, LocalDate contractEnd) {
		return jdbcClient.sql("""
				INSERT INTO app.organizations (name, plan, contract_end)
				VALUES (:name, :plan, :contractEnd)
				RETURNING id
				""")
				.param("name", "테스트 조직 " + System.nanoTime())
				.param("plan", plan)
				.param("contractEnd", contractEnd)
				.query(Long.class)
				.single();
	}

	private void addMember(long orgId, long userId) {
		jdbcClient.sql("""
				INSERT INTO app.organization_members (org_id, user_id)
				VALUES (:orgId, :userId)
				""")
				.param("orgId", orgId)
				.param("userId", userId)
				.update();
	}

	@Test
	void 무소속_유저는_FREE로_폴백한다() {
		Entitlements result = entitlementService.entitlementsFor(userId);

		assertThat(result.plan()).isEqualTo(Plan.FREE);
		assertThat(result.enabled()).isEmpty();
	}

	@Test
	void ENTERPRISE_조직_멤버는_plan이_ENTERPRISE다() {
		long orgId = insertOrganization("ENTERPRISE", null);
		addMember(orgId, userId);

		Entitlements result = entitlementService.entitlementsFor(userId);

		assertThat(result.plan()).isEqualTo(Plan.ENTERPRISE);
	}

	@Test
	void contract_end가_어제인_조직_멤버는_FREE로_폴백한다() {
		long orgId = insertOrganization("ENTERPRISE", LocalDate.now().minusDays(1));
		addMember(orgId, userId);

		Entitlements result = entitlementService.entitlementsFor(userId);

		assertThat(result.plan()).isEqualTo(Plan.FREE);
	}

	@Test
	void contract_end가_NULL이면_ENTERPRISE를_유지한다() {
		long orgId = insertOrganization("ENTERPRISE", null);
		addMember(orgId, userId);

		Entitlements result = entitlementService.entitlementsFor(userId);

		assertThat(result.plan()).isEqualTo(Plan.ENTERPRISE);
	}

	@Test
	void contract_end가_미래면_ENTERPRISE를_유지한다() {
		long orgId = insertOrganization("ENTERPRISE", LocalDate.now().plusDays(1));
		addMember(orgId, userId);

		Entitlements result = entitlementService.entitlementsFor(userId);

		assertThat(result.plan()).isEqualTo(Plan.ENTERPRISE);
	}
}
