package com.celfit.was.monitoring;

import com.celfit.was.archive.ArchiveReason;
import com.celfit.was.archive.ArchiveTables;
import com.celfit.was.archive.ArchiveWriter;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * app.monitoring_items CRUD(v3, V15) — 항상 활성(app 기본 DataSource, monitoring 서브시스템
 * 비활성이어도 무해). keywords는 jsonb ↔ String(원문) 왕복 — 파싱은 서비스/어셈블러 몫.
 */
@Repository
public class MonitoringItemRepository {

	private static final String SELECT_COLUMNS = """
			id, user_id, mode, registration_key, target_id, campaign_id, input_value, source_url,
			keywords::text AS keywords, tracking_days, registered_on, canceled_at, canceled_from,
			created_at
			""";

	private final JdbcClient jdbcClient;
	private final ArchiveWriter archiveWriter;

	public MonitoringItemRepository(JdbcClient jdbcClient, ArchiveWriter archiveWriter) {
		this.jdbcClient = jdbcClient;
		this.archiveWriter = archiveWriter;
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

	/**
	 * 등록 실패 롤백 — 삭제되는 item 행을 아카이브한다(트랙 NN).
	 *
	 * <p><b>실패 사유는 남지 않는다.</b> 사유는 monitoring_registration_entries의 reason_code·reason에
	 * 있는데, 그 조인 키인 entries.item_id는 이 삭제로 두 번 끊긴다 — FK가 ON DELETE SET NULL이고,
	 * 호출부가 곧이어 updateEntryResult(..., null, null)로 명시적으로도 지운다. 아카이브만으로
	 * "왜 실패했나"는 알 수 없으니 사유가 필요하면 entries를 봐야 한다.
	 *
	 * <p>호출부 3곳이 전부 등록 실패 롤백이라 사유를 REGISTRATION_ROLLBACK으로 못박았다. 다른 사유의
	 * item 삭제 지점이 생기면 ArchiveReason에 값을 추가하고 메서드를 나눌 것 — 이 메서드를 재사용하면
	 * 틀린 사유가 아카이브에 남는다.
	 *
	 * <p>app.brand_direct_posts.monitoring_item_id는 더 이상 CASCADE가 아니다(V20260811090500,
	 * ArchiveTables.BRAND_DIRECT_POSTS 참고) — 그대로 두면 남은 매핑 행이 이 item 삭제를 FK 위반으로
	 * 막는다. item보다 먼저 매핑 행을 찾아 아카이브·삭제한다.
	 */
	@Transactional
	public void delete(long itemId) {
		List<BrandDirectPostKey> directPostKeys = jdbcClient.sql("""
						SELECT user_id, short_code FROM app.brand_direct_posts WHERE monitoring_item_id = :itemId
						""")
				.param("itemId", itemId)
				.query(BrandDirectPostKey.class)
				.list();
		for (BrandDirectPostKey key : directPostKeys) {
			int archivedPost = archiveWriter.archiveByPk(ArchiveTables.BRAND_DIRECT_POSTS,
					ArchiveReason.REGISTRATION_ROLLBACK,
					Map.of("user_id", key.userId(), "short_code", key.shortCode()));
			int deletedPost = jdbcClient.sql(
							"DELETE FROM app.brand_direct_posts WHERE user_id = :userId AND short_code = :shortCode")
					.param("userId", key.userId())
					.param("shortCode", key.shortCode())
					.update();
			archiveWriter.verifyMatched(ArchiveTables.BRAND_DIRECT_POSTS, archivedPost, deletedPost);
		}

		int archived = archiveWriter.archiveByPk(ArchiveTables.MONITORING_ITEMS, ArchiveReason.REGISTRATION_ROLLBACK,
				Map.of("id", itemId));
		int deleted = jdbcClient.sql("DELETE FROM app.monitoring_items WHERE id = :itemId")
				.param("itemId", itemId)
				.update();
		archiveWriter.verifyMatched(ArchiveTables.MONITORING_ITEMS, archived, deleted);
	}

	/** app.brand_direct_posts의 복합 PK — item 롤백 시 이 item을 참조하는 매핑을 찾기 위한 내부 전용 행. */
	private record BrandDirectPostKey(long userId, String shortCode) {
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

	/**
	 * 어드민 조회 전용(설계 2026-08-01 §4 AdminMonitoringHealthService) — 유저 스코프 없이 전체 활성
	 * 후보(취소되지 않은 행 전부)를 가져온다. "활성" 최종 판정(pending 기간 만료·target 상태)은
	 * 서비스가 오늘(KST) 날짜를 받아 순수 함수로 가린다 — 여기서는 canceled_at만 거른다.
	 */
	public List<MonitoringItemRow> findAllNotCanceled() {
		return jdbcClient.sql("""
				SELECT %s
				FROM app.monitoring_items
				WHERE canceled_at IS NULL
				ORDER BY id ASC
				""".formatted(SELECT_COLUMNS))
				.query(MonitoringItemRow.class)
				.list();
	}

	/** 어드민 유저 상세(설계 §4 GET /v1/admin/users/{id})의 등록 이력 이벤트 — item_id로 배치 조회. */
	public List<MonitoringItemRow> findByIds(Collection<Long> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbcClient.sql("""
				SELECT %s
				FROM app.monitoring_items
				WHERE id IN (:ids)
				""".formatted(SELECT_COLUMNS))
				.param("ids", ids)
				.query(MonitoringItemRow.class)
				.list();
	}

	/**
	 * 어드민 유저 목록(설계 §4 GET /v1/admin/users)의 monitoringCount — 취소 포함 전량 누적이라
	 * canceled_at 조건 없이 유저별 전체 행 수를 센다.
	 */
	public Map<Long, Long> countByUsers(Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return jdbcClient.sql("""
				SELECT user_id, count(*) AS cnt
				FROM app.monitoring_items
				WHERE user_id IN (:ids)
				GROUP BY user_id
				""")
				.param("ids", userIds)
				.query((rs, rowNum) -> Map.entry(rs.getLong("user_id"), rs.getLong("cnt")))
				.list().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/**
	 * 어드민 캠페인 목록(GET /v1/admin/campaigns, 08-02)의 registrationCount — 캠페인 id 묶음 IN절
	 * 집계(N+1 금지). CampaignRepository.countItems와 동일하게 canceled_at 조건 없이 전량 누적
	 * (배정된 추적 행 총수 — 취소된 등록도 "한때 배정됐던" 기록으로 센다).
	 */
	public Map<Long, Long> countByCampaigns(Collection<Long> campaignIds) {
		if (campaignIds.isEmpty()) {
			return Map.of();
		}
		return jdbcClient.sql("""
				SELECT campaign_id, count(*) AS cnt
				FROM app.monitoring_items
				WHERE campaign_id IN (:ids)
				GROUP BY campaign_id
				""")
				.param("ids", campaignIds)
				.query((rs, rowNum) -> Map.entry(rs.getLong("campaign_id"), rs.getLong("cnt")))
				.list().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}
}
