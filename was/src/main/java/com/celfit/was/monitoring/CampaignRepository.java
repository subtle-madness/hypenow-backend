package com.celfit.was.monitoring;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.monitoring_campaigns CRUD(v3, V15). (user_id, name) 유니크 위반은 그대로 전파시켜
 * DuplicateKeyException으로 서비스가 409 변환하게 둔다.
 */
@Repository
public class CampaignRepository {

	private static final String RETURNING_COLUMNS =
			"id, user_id, name, description, start_date, end_date, brand, budget, seeding_count, created_at";

	private final JdbcClient jdbcClient;

	public CampaignRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public CampaignRow insert(long userId, String name, String description, LocalDate startDate,
			LocalDate endDate, String brand, Long budget, Integer seedingCount) {
		return jdbcClient.sql("""
				INSERT INTO app.monitoring_campaigns
				    (user_id, name, description, start_date, end_date, brand, budget, seeding_count)
				VALUES (:userId, :name, :description, :startDate, :endDate, :brand, :budget, :seedingCount)
				RETURNING %s
				""".formatted(RETURNING_COLUMNS))
				.param("userId", userId)
				.param("name", name)
				.param("description", description)
				.param("startDate", startDate)
				.param("endDate", endDate)
				.param("brand", brand)
				.param("budget", budget)
				.param("seedingCount", seedingCount)
				.query(CampaignRow.class)
				.single();
	}

	public Optional<CampaignRow> findByIdAndUser(long id, long userId) {
		return jdbcClient.sql("""
				SELECT %s FROM app.monitoring_campaigns WHERE id = :id AND user_id = :userId
				""".formatted(RETURNING_COLUMNS))
				.param("id", id)
				.param("userId", userId)
				.query(CampaignRow.class)
				.optional();
	}

	public Optional<CampaignRow> findByNameAndUser(String name, long userId) {
		return jdbcClient.sql("""
				SELECT %s FROM app.monitoring_campaigns WHERE name = :name AND user_id = :userId
				""".formatted(RETURNING_COLUMNS))
				.param("name", name)
				.param("userId", userId)
				.query(CampaignRow.class)
				.optional();
	}

	public List<CampaignRow> findByUser(long userId) {
		return jdbcClient.sql("""
				SELECT %s FROM app.monitoring_campaigns WHERE user_id = :userId ORDER BY created_at ASC, id ASC
				""".formatted(RETURNING_COLUMNS))
				.param("userId", userId)
				.query(CampaignRow.class)
				.list();
	}

	/** 전 필드 갱신 — 부분 갱신 의미론(미지정 필드는 기존 값 유지)은 서비스가 기존 값과 머지해서 호출. */
	public CampaignRow update(long id, String name, String description, LocalDate startDate,
			LocalDate endDate, String brand, Long budget, Integer seedingCount) {
		return jdbcClient.sql("""
				UPDATE app.monitoring_campaigns
				SET name = :name, description = :description, start_date = :startDate, end_date = :endDate,
				    brand = :brand, budget = :budget, seeding_count = :seedingCount
				WHERE id = :id
				RETURNING %s
				""".formatted(RETURNING_COLUMNS))
				.param("id", id)
				.param("name", name)
				.param("description", description)
				.param("startDate", startDate)
				.param("endDate", endDate)
				.param("brand", brand)
				.param("budget", budget)
				.param("seedingCount", seedingCount)
				.query(CampaignRow.class)
				.single();
	}

	public void delete(long id) {
		jdbcClient.sql("DELETE FROM app.monitoring_campaigns WHERE id = :id")
				.param("id", id)
				.update();
	}

	/** 캠페인 삭제 확인 모달·검증용 — 배정된 추적 아이템 수. */
	public long countItems(long campaignId) {
		return jdbcClient.sql("SELECT count(*) FROM app.monitoring_items WHERE campaign_id = :campaignId")
				.param("campaignId", campaignId)
				.query(Long.class)
				.single();
	}

	/**
	 * 어드민 전체 캠페인 목록(GET /v1/admin/campaigns, 프론트 변경요청서 4-2-6절, 08-02) — 유저
	 * 스코프 없이 전체를 created_at 내림차순으로 반환한다(파라미터 없음, 페이지네이션 없음).
	 */
	public List<CampaignRow> findAllOrderByCreatedAtDesc() {
		return jdbcClient.sql("""
				SELECT %s FROM app.monitoring_campaigns ORDER BY created_at DESC
				""".formatted(RETURNING_COLUMNS))
				.query(CampaignRow.class)
				.list();
	}

	/**
	 * 어드민 조회 전용(설계 2026-08-01 §4) — 여러 유저에 걸친 캠페인 id 묶음의 이름 조회
	 * (GET /v1/admin/monitoring/registrations의 campaignName Java 결합용). 유저 스코프 없음.
	 */
	public List<CampaignRow> findByIds(Collection<Long> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbcClient.sql("""
				SELECT %s FROM app.monitoring_campaigns WHERE id IN (:ids)
				""".formatted(RETURNING_COLUMNS))
				.param("ids", ids)
				.query(CampaignRow.class)
				.list();
	}

	/** 어드민 유저 목록(설계 §4)의 campaignCount — 유저 id 묶음 IN절 집계(전 유저 스캔 금지). */
	public Map<Long, Long> countByUsers(Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return jdbcClient.sql("""
				SELECT user_id, count(*) AS cnt
				FROM app.monitoring_campaigns
				WHERE user_id IN (:ids)
				GROUP BY user_id
				""")
				.param("ids", userIds)
				.query((rs, rowNum) -> Map.entry(rs.getLong("user_id"), rs.getLong("cnt")))
				.list().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}
}
