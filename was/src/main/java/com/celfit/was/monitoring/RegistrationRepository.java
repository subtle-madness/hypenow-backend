package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.monitoring_registrations·app.monitoring_registration_entries CRUD(6.28) — 요청 1행 +
 * 건별 결과(입력 순서 보존). 목록 조립은 쿼리 2회(헤더 + 엔트리)로 하고 자바에서 묶는다.
 */
@Repository
public class RegistrationRepository {

	private final JdbcClient jdbcClient;

	public RegistrationRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 등록 요청 1행 선저장 — requested_at DEFAULT now(). RETURNING id. */
	public long insert(long userId) {
		return jdbcClient.sql("INSERT INTO app.monitoring_registrations (user_id) VALUES (:userId) RETURNING id")
				.param("userId", userId)
				.query(Long.class)
				.single();
	}

	public void insertEntry(long registrationId, int seq, String input, String kind, String result,
			String reasonCode, String reason, String resolvedUrl, Long itemId) {
		jdbcClient.sql("""
				INSERT INTO app.monitoring_registration_entries
					(registration_id, seq, input, kind, result, reason_code, reason, resolved_url, item_id)
				VALUES (:registrationId, :seq, :input, :kind, :result, :reasonCode, :reason, :resolvedUrl, :itemId)
				""")
				.param("registrationId", registrationId)
				.param("seq", seq)
				.param("input", input)
				.param("kind", kind)
				.param("result", result)
				.param("reasonCode", reasonCode)
				.param("reason", reason)
				.param("resolvedUrl", resolvedUrl)
				.param("itemId", itemId)
				.update();
	}

	public void updateEntryResult(long registrationId, int seq, String result, String reasonCode,
			String reason, String resolvedUrl, Long itemId) {
		jdbcClient.sql("""
				UPDATE app.monitoring_registration_entries
				SET result = :result, reason_code = :reasonCode, reason = :reason,
				    resolved_url = :resolvedUrl, item_id = :itemId
				WHERE registration_id = :registrationId AND seq = :seq
				""")
				.param("result", result)
				.param("reasonCode", reasonCode)
				.param("reason", reason)
				.param("resolvedUrl", resolvedUrl)
				.param("itemId", itemId)
				.param("registrationId", registrationId)
				.param("seq", seq)
				.update();
	}

	/** 미정산(pending) 엔트리가 0건이면 완료 처리 — 이미 완료면 no-op(완료 갱신 재실행되지 않음). */
	public void markCompletedIfAllSettled(long registrationId) {
		jdbcClient.sql("""
				UPDATE app.monitoring_registrations
				SET completed_at = now()
				WHERE id = :registrationId
				  AND completed_at IS NULL
				  AND NOT EXISTS (
				      SELECT 1 FROM app.monitoring_registration_entries
				      WHERE registration_id = :registrationId AND result = 'pending'
				  )
				""")
				.param("registrationId", registrationId)
				.update();
	}

	/** 최근 등록 이력 — requested_at DESC(id DESC tie-break), 각 행의 entries는 seq ASC로 채워 반환. */
	public List<RegistrationRow> findRecentByUser(long userId, int limit) {
		List<RegistrationHeader> headers = jdbcClient.sql("""
				SELECT id, user_id, requested_at, completed_at
				FROM app.monitoring_registrations
				WHERE user_id = :userId
				ORDER BY requested_at DESC, id DESC
				LIMIT :limit
				""")
				.param("userId", userId)
				.param("limit", limit)
				.query(RegistrationHeader.class)
				.list();
		if (headers.isEmpty()) {
			return List.of();
		}

		List<Long> ids = headers.stream().map(RegistrationHeader::id).toList();
		List<RegistrationEntryRow> entries = jdbcClient.sql("""
				SELECT registration_id, seq, input, kind, result, reason_code, reason, resolved_url, item_id
				FROM app.monitoring_registration_entries
				WHERE registration_id IN (:ids)
				ORDER BY registration_id ASC, seq ASC
				""")
				.param("ids", ids)
				.query(RegistrationEntryRow.class)
				.list();
		Map<Long, List<RegistrationEntryRow>> byRegistration =
				entries.stream().collect(Collectors.groupingBy(RegistrationEntryRow::registrationId));

		return headers.stream()
				.map(h -> new RegistrationRow(h.id(), h.userId(), h.requestedAt(), h.completedAt(),
						byRegistration.getOrDefault(h.id(), List.of())))
				.toList();
	}

	public long countByUser(long userId) {
		return jdbcClient.sql("SELECT count(*) FROM app.monitoring_registrations WHERE user_id = :userId")
				.param("userId", userId)
				.query(Long.class)
				.single();
	}

	/** entries 없이 헤더만 매핑하기 위한 내부 전용 행 — RecordRowMapper 컬럼 매칭용. */
	private record RegistrationHeader(long id, long userId, OffsetDateTime requestedAt, OffsetDateTime completedAt) {
	}
}
