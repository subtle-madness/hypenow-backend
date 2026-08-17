package com.celfit.was.entitlement;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * entitlement 판정 읽기 전용 조회 — 조직 생성·멤버·오버라이드 쓰기는 OrganizationRepository(Task 3) 소관,
 * 이 리포지토리는 {@link EntitlementService}가 소비하는 조회만 담당한다.
 * jsonb는 SQL에서 {@code ::text}로 캐스팅해 String으로 받은 뒤 ObjectMapper로 파싱한다
 * (MonitoringItemRepository·DigestRepository의 jsonb 읽기 관용구 재사용 — was는 커스텀 RowMapper를 쓰지 않는다).
 */
@Repository
public class EntitlementRepository {

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public EntitlementRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	/**
	 * 유저의 조직 멤버십+조직 조인 단건 조회. app.organization_members.user_id가 UNIQUE라
	 * 유저당 최대 1행 — 무소속이면 empty(호출부가 FREE로 폴백).
	 */
	public Optional<Membership> findMembership(long userId) {
		return jdbcClient.sql("""
				SELECT o.id AS org_id, o.plan AS plan, o.contract_end AS contract_end
				FROM app.organization_members m
				JOIN app.organizations o ON o.id = m.org_id
				WHERE m.user_id = :userId
				""")
				.param("userId", userId)
				.query(Membership.class)
				.optional();
	}

	/**
	 * 조직의 기능 오버라이드 전체 목록. feature_key는 raw 문자열 그대로 돌려준다 —
	 * FeatureKey enum 매핑·미등록 키 무시는 {@link EntitlementService#compose}의 책임이다.
	 */
	public List<FeatureOverride> findOverrides(long orgId) {
		return jdbcClient.sql("""
				SELECT feature_key, enabled, value::text AS value
				FROM app.organization_feature_overrides
				WHERE org_id = :orgId
				""")
				.param("orgId", orgId)
				.query(FeatureOverrideRow.class)
				.list()
				.stream()
				.map(row -> new FeatureOverride(row.featureKey(), row.enabled(), parseValue(row.value())))
				.toList();
	}

	private JsonNode parseValue(String json) {
		return json == null ? null : objectMapper.readTree(json);
	}

	/** 멤버십 조인 결과 — plan은 raw 문자열(app.organizations.plan CHECK 제약이 Plan enum 값만 허용). */
	public record Membership(long orgId, String plan, LocalDate contractEnd) {
	}

	/** DB row 그대로(jsonb는 ::text 캐스팅된 텍스트) — {@link #findOverrides}가 JsonNode로 변환하기 전 중간 형태. */
	private record FeatureOverrideRow(String featureKey, boolean enabled, String value) {
	}

	/** 오버라이드 1건 — value는 파싱된 JsonNode(NULL이면 null). {@link EntitlementService#compose}가 소비. */
	public record FeatureOverride(String featureKey, boolean enabled, JsonNode value) {
	}
}
