package com.celfit.was.monitoring;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.monitoring_items CRUD(v3, V15) — 항상 활성(app 기본 DataSource, monitoring 서브시스템
 * 비활성이어도 무해). keywords는 jsonb ↔ String(원문) 왕복 — 파싱은 서비스/어셈블러 몫.
 */
@Repository
public class MonitoringItemRepository {

	private static final String SELECT_COLUMNS = """
			id, user_id, mode, registration_key, target_id, campaign_id, input_value, source_url,
			keywords::text AS keywords, tracking_days, registered_on, canceled_at, canceled_from,
			started_notified_on, created_at
			""";

	private final JdbcClient jdbcClient;

	public MonitoringItemRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 등록 1단계 — target_id NULL의 pending 행 선저장. RETURNING id. */
	public long insertPending(long userId, String mode, UUID registrationKey, Long campaignId,
			String inputValue, String sourceUrl, String keywordsJson, int trackingDays, LocalDate registeredOn) {
		return jdbcClient.sql("""
				INSERT INTO app.monitoring_items
					(user_id, mode, registration_key, campaign_id, input_value, source_url, keywords,
					 tracking_days, registered_on)
				VALUES (:userId, :mode, :registrationKey, :campaignId, :inputValue, :sourceUrl,
					CAST(:keywords AS jsonb), :trackingDays, :registeredOn)
				RETURNING id
				""")
				.param("userId", userId)
				.param("mode", mode)
				.param("registrationKey", registrationKey)
				.param("campaignId", campaignId)
				.param("inputValue", inputValue)
				.param("sourceUrl", sourceUrl)
				.param("keywords", keywordsJson)
				.param("trackingDays", trackingDays)
				.param("registeredOn", registeredOn)
				.query(Long.class)
				.single();
	}

	/** 등록 2단계 — target_id 확정. 매핑 행이 없으면(무효 id) 예외. */
	public void confirmTarget(long itemId, long targetId) {
		int updated = jdbcClient.sql("UPDATE app.monitoring_items SET target_id = :targetId WHERE id = :itemId")
				.param("targetId", targetId)
				.param("itemId", itemId)
				.update();
		if (updated != 1) {
			throw new IllegalStateException("등록 확정 실패 — monitoring_items 행 없음: id=" + itemId);
		}
	}

	public void delete(long itemId) {
		jdbcClient.sql("DELETE FROM app.monitoring_items WHERE id = :itemId")
				.param("itemId", itemId)
				.update();
	}

	public Optional<MonitoringItemRow> findByIdAndUser(long id, long userId) {
		return jdbcClient.sql("SELECT " + SELECT_COLUMNS + " FROM app.monitoring_items WHERE id = :id AND user_id = :userId")
				.param("id", id)
				.param("userId", userId)
				.query(MonitoringItemRow.class)
				.optional();
	}

	public List<MonitoringItemRow> findByUser(long userId) {
		return jdbcClient.sql("""
				SELECT %s
				FROM app.monitoring_items
				WHERE user_id = :userId
				ORDER BY registered_on ASC, id ASC
				""".formatted(SELECT_COLUMNS))
				.param("userId", userId)
				.query(MonitoringItemRow.class)
				.list();
	}

	/** 진행 중(취소되지 않은) 동일 입력 후보 — target 종결 여부 재확인은 서비스 몫. */
	public List<MonitoringItemRow> findActiveByInput(long userId, String mode, String inputValue) {
		return jdbcClient.sql("""
				SELECT %s
				FROM app.monitoring_items
				WHERE user_id = :userId AND mode = :mode AND input_value = :inputValue AND canceled_at IS NULL
				""".formatted(SELECT_COLUMNS))
				.param("userId", userId)
				.param("mode", mode)
				.param("inputValue", inputValue)
				.query(MonitoringItemRow.class)
				.list();
	}

	public void updateTrackingDays(long itemId, int trackingDays) {
		jdbcClient.sql("UPDATE app.monitoring_items SET tracking_days = :trackingDays WHERE id = :itemId")
				.param("trackingDays", trackingDays)
				.param("itemId", itemId)
				.update();
	}

	/** campaignId null이면 캠페인 배정 해제. */
	public void updateCampaign(long itemId, Long campaignId) {
		jdbcClient.sql("UPDATE app.monitoring_items SET campaign_id = :campaignId WHERE id = :itemId")
				.param("campaignId", campaignId)
				.param("itemId", itemId)
				.update();
	}

	public void markCanceled(long itemId, String canceledFrom, OffsetDateTime at) {
		jdbcClient.sql("""
				UPDATE app.monitoring_items
				SET canceled_at = :at, canceled_from = :canceledFrom
				WHERE id = :itemId
				""")
				.param("at", at)
				.param("canceledFrom", canceledFrom)
				.param("itemId", itemId)
				.update();
	}

	/** 다이제스트 발화 중복 방지용 마킹 — 빈 리스트는 no-op(IN () SQL 오류 방지). */
	public void markStartedNotified(List<Long> itemIds, LocalDate on) {
		if (itemIds.isEmpty()) {
			return;
		}
		jdbcClient.sql("UPDATE app.monitoring_items SET started_notified_on = :on WHERE id IN (:itemIds)")
				.param("on", on)
				.param("itemIds", itemIds)
				.update();
	}

	/**
	 * 탈퇴 해지 루프(AccountDeletionService) 전용 — target 확정 & 미종결 행의 target_id만.
	 * id ASC로 정렬해 해지 호출 순서를 결정론적으로 만든다(테스트 재현성).
	 */
	public List<Long> findActiveTargetIds(long userId) {
		return jdbcClient.sql("""
				SELECT target_id FROM app.monitoring_items
				WHERE user_id = :userId AND target_id IS NOT NULL AND canceled_at IS NULL
				ORDER BY id ASC
				""")
				.param("userId", userId)
				.query(Long.class)
				.list();
	}

	/** pending(target 미확정) 상태로 age 이상 방치된 행 — 크래시 복구 배치 후보. */
	public List<MonitoringItemRow> findPendingOlderThan(Duration age) {
		return jdbcClient.sql("""
				SELECT %s
				FROM app.monitoring_items
				WHERE target_id IS NULL AND canceled_at IS NULL
				  AND created_at < now() - make_interval(secs => :seconds)
				""".formatted(SELECT_COLUMNS))
				.param("seconds", (double) age.toMillis() / 1000.0)
				.query(MonitoringItemRow.class)
				.list();
	}
}
