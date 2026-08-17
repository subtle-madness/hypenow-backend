package com.celfit.was.entitlement;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 조직 생성·멤버·오버라이드 쓰기(계획 Task 3) — {@link EntitlementRepository}는 판정 읽기 전용으로 유지하고,
 * 어드민 API가 쓰는 CRUD는 전부 이 리포지토리가 담당한다. jsonb는 EntitlementRepository와 동일하게
 * {@code CAST(:param AS jsonb)} 쓰기 / {@code ::text} 읽기 관용구를 재사용한다(GateEventRepository·
 * SignupEventRecorder 선례).
 */
@Repository
public class OrganizationRepository {

	private static final Set<String> PATCHABLE_COLUMNS = Set.of("plan", "contract_start", "contract_end");

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public OrganizationRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	public long create(String name, String plan, LocalDate contractStart, LocalDate contractEnd) {
		return jdbcClient.sql("""
				INSERT INTO app.organizations (name, plan, contract_start, contract_end)
				VALUES (:name, :plan, :contractStart, :contractEnd)
				RETURNING id
				""")
				.param("name", name)
				.param("plan", plan)
				.param("contractStart", contractStart)
				.param("contractEnd", contractEnd)
				.query(Long.class)
				.single();
	}

	public Optional<OrganizationRow> findById(long id) {
		return jdbcClient.sql("""
				SELECT id, name, plan, contract_start, contract_end, created_at
				FROM app.organizations WHERE id = :id
				""")
				.param("id", id)
				.query(OrganizationRow.class)
				.optional();
	}

	/** 목록(AdminUserRepository.findPage 관용구 — created_at DESC 고정 정렬, sort 파라미터 없음). */
	public Page findPage(int limit, int offset) {
		List<OrganizationRow> rows = jdbcClient.sql("""
				SELECT id, name, plan, contract_start, contract_end, created_at
				FROM app.organizations ORDER BY created_at DESC
				LIMIT :limit OFFSET :offset
				""")
				.param("limit", limit)
				.param("offset", offset)
				.query(OrganizationRow.class)
				.list();
		long total = jdbcClient.sql("SELECT count(*) FROM app.organizations").query(Long.class).single();
		return new Page(rows, total);
	}

	/**
	 * PATCH — columns는 patch(plan/contract_start/contract_end) 화이트리스트 밖 키가 있으면 예외
	 * (UserRepository.patchProfile 관용구). 대상 부재면 empty(호출부가 404로 변환).
	 */
	public Optional<OrganizationRow> patch(long id, Map<String, Object> columns) {
		if (columns.isEmpty()) {
			return findById(id);
		}
		if (!PATCHABLE_COLUMNS.containsAll(columns.keySet())) {
			throw new IllegalArgumentException("patch 화이트리스트 밖 컬럼: " + columns.keySet());
		}
		List<String> sets = columns.keySet().stream().map(column -> column + " = :" + column).toList();
		JdbcClient.StatementSpec spec = jdbcClient
				.sql("UPDATE app.organizations SET " + String.join(", ", sets) + " WHERE id = :id")
				.param("id", id);
		for (Map.Entry<String, Object> entry : columns.entrySet()) {
			spec = spec.param(entry.getKey(), entry.getValue());
		}
		int updated = spec.update();
		return updated == 0 ? Optional.empty() : findById(id);
	}

	/** 조직 멤버 목록 — 이메일은 어드민·org 셀프서비스 응답 공용으로 조인해 함께 내려준다. */
	public List<MemberRow> findMembers(long orgId) {
		return jdbcClient.sql("""
				SELECT m.user_id AS user_id, u.email AS email, m.org_role AS org_role
				FROM app.organization_members m
				JOIN app.users u ON u.id = m.user_id
				WHERE m.org_id = :orgId
				ORDER BY m.created_at
				""")
				.param("orgId", orgId)
				.query(MemberRow.class)
				.list();
	}

	/** 멤버 1건 — POST 배정 직후 응답 조립용(AdminOrganizationsController). */
	public Optional<MemberRow> findMember(long orgId, long userId) {
		return jdbcClient.sql("""
				SELECT m.user_id AS user_id, u.email AS email, m.org_role AS org_role
				FROM app.organization_members m
				JOIN app.users u ON u.id = m.user_id
				WHERE m.org_id = :orgId AND m.user_id = :userId
				""")
				.param("orgId", orgId)
				.param("userId", userId)
				.query(MemberRow.class)
				.optional();
	}

	/** 요청자(userId)의 소속 조직·역할 — org 셀프서비스(Task 4)가 매 요청 재해석한다(세션 불신). */
	public Optional<SelfMembership> findMembershipByUserId(long userId) {
		return jdbcClient.sql("""
				SELECT org_id AS org_id, org_role AS org_role
				FROM app.organization_members WHERE user_id = :userId
				""")
				.param("userId", userId)
				.query(SelfMembership.class)
				.optional();
	}

	/**
	 * 멤버 추가 — org_id UNIQUE(user_id) 제약이 "이미 타 조직 소속"·"이미 이 조직 소속" 둘 다
	 * DuplicateKeyException으로 던진다(SignupService 관용구, 호출부가 409로 변환).
	 */
	public void addMember(long orgId, long userId, String orgRole) {
		jdbcClient.sql("""
				INSERT INTO app.organization_members (org_id, user_id, org_role)
				VALUES (:orgId, :userId, :orgRole)
				""")
				.param("orgId", orgId)
				.param("userId", userId)
				.param("orgRole", orgRole)
				.update();
	}

	public boolean updateMemberRole(long orgId, long userId, String orgRole) {
		int updated = jdbcClient.sql("""
				UPDATE app.organization_members SET org_role = :orgRole
				WHERE org_id = :orgId AND user_id = :userId
				""")
				.param("orgRole", orgRole)
				.param("orgId", orgId)
				.param("userId", userId)
				.update();
		return updated > 0;
	}

	public boolean removeMember(long orgId, long userId) {
		int updated = jdbcClient.sql("DELETE FROM app.organization_members WHERE org_id = :orgId AND user_id = :userId")
				.param("orgId", orgId)
				.param("userId", userId)
				.update();
		return updated > 0;
	}

	/** 오버라이드 목록(어드민 상세 GET 전용) — EntitlementRepository.findOverrides와 같은 파싱, 리턴 형만 다르다. */
	public List<OverrideRow> findOverrides(long orgId) {
		return jdbcClient.sql("""
				SELECT feature_key, enabled, value::text AS value
				FROM app.organization_feature_overrides
				WHERE org_id = :orgId
				ORDER BY feature_key
				""")
				.param("orgId", orgId)
				.query(OverrideRowRaw.class)
				.list()
				.stream()
				.map(row -> new OverrideRow(row.featureKey(), row.enabled(), parseValue(row.value())))
				.toList();
	}

	/** upsert(PUT 시맨틱) — enabled/value 갱신, updated_at 항상 now(). */
	public void upsertOverride(long orgId, String featureKey, boolean enabled, JsonNode value) {
		String json = value == null ? null : objectMapper.writeValueAsString(value);
		jdbcClient.sql("""
				INSERT INTO app.organization_feature_overrides (org_id, feature_key, enabled, value, updated_at)
				VALUES (:orgId, :featureKey, :enabled, CAST(:value AS jsonb), now())
				ON CONFLICT (org_id, feature_key)
				DO UPDATE SET enabled = :enabled, value = CAST(:value AS jsonb), updated_at = now()
				""")
				.param("orgId", orgId)
				.param("featureKey", featureKey)
				.param("enabled", enabled)
				.param("value", json)
				.update();
	}

	/**
	 * 삭제는 FeatureKey enum 유효성과 무관하게 raw 문자열을 그대로 받는다 — enum에서 빠진 키의 잔재 행도
	 * 어드민이 정리할 수 있어야 한다(EntitlementService.compose가 무시·warn만 하고 삭제는 안 하는 것과 대칭).
	 */
	public boolean deleteOverride(long orgId, String featureKey) {
		int deleted = jdbcClient.sql("""
				DELETE FROM app.organization_feature_overrides WHERE org_id = :orgId AND feature_key = :featureKey
				""")
				.param("orgId", orgId)
				.param("featureKey", featureKey)
				.update();
		return deleted > 0;
	}

	private JsonNode parseValue(String json) {
		return json == null ? null : objectMapper.readTree(json);
	}

	public record OrganizationRow(long id, String name, String plan, LocalDate contractStart, LocalDate contractEnd,
			OffsetDateTime createdAt) {
	}

	public record MemberRow(long userId, String email, String orgRole) {
	}

	public record SelfMembership(long orgId, String orgRole) {
	}

	public record OverrideRow(String featureKey, boolean enabled, JsonNode value) {
	}

	private record OverrideRowRaw(String featureKey, boolean enabled, String value) {
	}

	public record Page(List<OrganizationRow> rows, long total) {
	}
}
