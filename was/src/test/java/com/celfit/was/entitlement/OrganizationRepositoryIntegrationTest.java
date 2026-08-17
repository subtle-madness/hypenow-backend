package com.celfit.was.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import com.celfit.was.entitlement.OrganizationRepository.MemberRow;
import com.celfit.was.entitlement.OrganizationRepository.OrganizationRow;
import com.celfit.was.entitlement.OrganizationRepository.OverrideRow;
import com.celfit.was.entitlement.OrganizationRepository.SelfMembership;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link OrganizationRepository} 실 DB 검증(계획 Task 3) — 어드민 컨트롤러 IT가 못 도달하는
 * 오버라이드 저장(FeatureKey enum 우회, 임의 문자열 키)까지 포함한다: PUT /overrides API는
 * FeatureKey enum이 빈 상태라 항상 400을 내므로, 실제 upsert·delete SQL은 여기서만 검증 가능하다
 * (계획 Task 3 주의사항).
 */
class OrganizationRepositoryIntegrationTest extends IntegrationTest {

	@Autowired
	OrganizationRepository organizationRepository;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	ObjectMapper objectMapper;

	private long userId;

	@BeforeEach
	void seedUser() {
		userId = jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type, agreed_terms,
				                       agreed_privacy, agreed_age14)
				VALUES (:email, 'x', 'USER', '테스트', 'brand', true, true, true)
				RETURNING id
				""")
				.param("email", "org-repo-" + System.nanoTime() + "@test.io")
				.query(Long.class)
				.single();
	}

	@Test
	void 조직_생성과_조회() {
		long orgId = organizationRepository.create("테스트 조직", "ENTERPRISE", LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 12, 31));

		OrganizationRow row = organizationRepository.findById(orgId).orElseThrow();

		assertThat(row.name()).isEqualTo("테스트 조직");
		assertThat(row.plan()).isEqualTo("ENTERPRISE");
		assertThat(row.contractStart()).isEqualTo(LocalDate.of(2026, 1, 1));
		assertThat(row.contractEnd()).isEqualTo(LocalDate.of(2026, 12, 31));
	}

	@Test
	void patch는_지정한_컬럼만_바꾸고_null도_명시적으로_반영한다() {
		long orgId = organizationRepository.create("조직", "ENTERPRISE", LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 12, 31));

		Map<String, Object> columns = new LinkedHashMap<>();
		columns.put("contract_end", null);
		OrganizationRow patched = organizationRepository.patch(orgId, columns).orElseThrow();

		assertThat(patched.contractEnd()).isNull();
		assertThat(patched.contractStart()).isEqualTo(LocalDate.of(2026, 1, 1)); // 미지정 컬럼은 유지
	}

	@Test
	void patch_대상_부재면_empty() {
		Map<String, Object> columns = Map.of("plan", "FREE");

		assertThat(organizationRepository.patch(999_999_999L, columns)).isEmpty();
	}

	@Test
	void 멤버_배정_조회_역할변경_해지() {
		long orgId = organizationRepository.create("조직", "ENTERPRISE", null, null);

		organizationRepository.addMember(orgId, userId, "MEMBER");

		MemberRow member = organizationRepository.findMember(orgId, userId).orElseThrow();
		assertThat(member.orgRole()).isEqualTo("MEMBER");
		assertThat(organizationRepository.findMembers(orgId)).extracting(MemberRow::userId).containsExactly(userId);

		SelfMembership self = organizationRepository.findMembershipByUserId(userId).orElseThrow();
		assertThat(self.orgId()).isEqualTo(orgId);
		assertThat(self.orgRole()).isEqualTo("MEMBER");

		assertThat(organizationRepository.updateMemberRole(orgId, userId, "ORG_ADMIN")).isTrue();
		assertThat(organizationRepository.findMember(orgId, userId).orElseThrow().orgRole()).isEqualTo("ORG_ADMIN");

		assertThat(organizationRepository.removeMember(orgId, userId)).isTrue();
		assertThat(organizationRepository.findMember(orgId, userId)).isEmpty();
	}

	@Test
	void 이미_타_조직_소속_유저_추가는_DuplicateKeyException() {
		long org1 = organizationRepository.create("조직1", "ENTERPRISE", null, null);
		long org2 = organizationRepository.create("조직2", "ENTERPRISE", null, null);
		organizationRepository.addMember(org1, userId, "MEMBER");

		assertThatThrownBy(() -> organizationRepository.addMember(org2, userId, "MEMBER"))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void 없는_멤버_역할변경_해지는_false() {
		long orgId = organizationRepository.create("조직", "ENTERPRISE", null, null);

		assertThat(organizationRepository.updateMemberRole(orgId, userId, "ORG_ADMIN")).isFalse();
		assertThat(organizationRepository.removeMember(orgId, userId)).isFalse();
	}

	/**
	 * FeatureKey enum이 빈 상태라 어드민 API로는 도달 불가한 경로 — repository를 직접 호출해 임의
	 * 문자열 키(계획 Task 3 주의사항)로 upsert/조회/delete SQL 자체를 검증한다.
	 */
	@Test
	void 오버라이드_upsert_조회_삭제는_임의_문자열_키로도_동작한다() {
		long orgId = organizationRepository.create("조직", "ENTERPRISE", null, null);
		JsonNode value = objectMapper.readTree("{\"depth\":100}");

		organizationRepository.upsertOverride(orgId, "SOME_ARBITRARY_KEY", true, value);

		List<OverrideRow> overrides = organizationRepository.findOverrides(orgId);
		assertThat(overrides).hasSize(1);
		assertThat(overrides.get(0).featureKey()).isEqualTo("SOME_ARBITRARY_KEY");
		assertThat(overrides.get(0).enabled()).isTrue();
		assertThat(overrides.get(0).value().get("depth").asInt()).isEqualTo(100);

		// 같은 키 재-upsert는 갱신(ON CONFLICT) — enabled 뒤집고 value 제거
		organizationRepository.upsertOverride(orgId, "SOME_ARBITRARY_KEY", false, null);
		OverrideRow updated = organizationRepository.findOverrides(orgId).get(0);
		assertThat(updated.enabled()).isFalse();
		assertThat(updated.value()).isNull();

		assertThat(organizationRepository.deleteOverride(orgId, "SOME_ARBITRARY_KEY")).isTrue();
		assertThat(organizationRepository.findOverrides(orgId)).isEmpty();
		assertThat(organizationRepository.deleteOverride(orgId, "SOME_ARBITRARY_KEY")).isFalse();
	}

	@Test
	void 목록_페이지네이션과_총건수() {
		organizationRepository.create("조직A", "FREE", null, null);
		organizationRepository.create("조직B", "ENTERPRISE", null, null);

		OrganizationRepository.Page page = organizationRepository.findPage(1, 0);

		assertThat(page.total()).isGreaterThanOrEqualTo(2);
		assertThat(page.rows()).hasSize(1);
	}

	@Test
	void 무소속_유저는_findMembershipByUserId가_empty() {
		Optional<SelfMembership> membership = organizationRepository.findMembershipByUserId(userId);

		assertThat(membership).isEmpty();
	}
}
