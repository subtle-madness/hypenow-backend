package com.celfit.was.monitoring;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.monitoring_campaigns CRUD(v3, V15). (user_id, name) 유니크 위반은 그대로 전파시켜
 * DuplicateKeyException으로 서비스가 409 변환하게 둔다.
 */
@Repository
public class CampaignRepository {

	private static final String RETURNING_COLUMNS =
			"id, user_id, name, description, start_date, end_date, brand, budget, created_at";

	private final JdbcClient jdbcClient;

	public CampaignRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public CampaignRow insert(long userId, String name, String description, LocalDate startDate,
			LocalDate endDate, String brand, Long budget) {
		return jdbcClient.sql("""
				INSERT INTO app.monitoring_campaigns (user_id, name, description, start_date, end_date, brand, budget)
				VALUES (:userId, :name, :description, :startDate, :endDate, :brand, :budget)
				RETURNING %s
				""".formatted(RETURNING_COLUMNS))
				.param("userId", userId)
				.param("name", name)
				.param("description", description)
				.param("startDate", startDate)
				.param("endDate", endDate)
				.param("brand", brand)
				.param("budget", budget)
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
			LocalDate endDate, String brand, Long budget) {
		return jdbcClient.sql("""
				UPDATE app.monitoring_campaigns
				SET name = :name, description = :description, start_date = :startDate, end_date = :endDate,
				    brand = :brand, budget = :budget
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
				.query(CampaignRow.class)
				.single();
	}

	public void delete(long id) {
		jdbcClient.sql("DELETE FROM app.monitoring_campaigns WHERE id = :id")
				.param("id", id)
				.update();
	}

	/** 캠페인 삭제 확인 모달·검증용 — 배정된 추적 아이템 수. */
	public int countItems(long campaignId) {
		return jdbcClient.sql("SELECT count(*)::int FROM app.monitoring_items WHERE campaign_id = :campaignId")
				.param("campaignId", campaignId)
				.query(Integer.class)
				.single();
	}
}
