package com.celfit.was.monitoring;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.monitoring_registrations·app.monitoring_registration_entries CRUD(6.28) — 요청 1행 +
 * 건별 결과(입력 순서 보존). 목록 조립은 쿼리 2회(헤더 + 엔트리)로 하고 자바에서 묶는다.
 */
@Repository
public class RegistrationRepository {

	private static final String CANCELED_REASON = "등록을 취소했어요.";
	private static final String STALE_INTERNAL_ERROR_REASON = "24시간 넘게 처리되지 않아 실패로 확정했어요.";

	private final JdbcClient jdbcClient;

	public RegistrationRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * 등록 요청 1행 선저장 — requested_at DEFAULT now(). RETURNING id.
	 * trackingDays·campaignId(V17)는 요청 시점 값의 보존본 — share 항목이 해소 후 실행기에서
	 * monitoring_items 행을 늦게 만들 때 참조한다(RegistrationRow 클래스 문서 참조).
	 */
	public long insert(long userId, int trackingDays, Long campaignId) {
		return jdbcClient.sql("""
				INSERT INTO app.monitoring_registrations (user_id, tracking_days, campaign_id)
				VALUES (:userId, :trackingDays, :campaignId)
				RETURNING id
				""")
				.param("userId", userId)
				.param("trackingDays", trackingDays)
				.param("campaignId", campaignId)
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
				SELECT id, user_id, requested_at, completed_at, tracking_days, campaign_id, acknowledged_at
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
		Map<Long, List<RegistrationEntryRow>> byRegistration = entriesByRegistration(ids);

		return headers.stream()
				.map(h -> new RegistrationRow(h.id(), h.userId(), h.requestedAt(), h.completedAt(),
						h.trackingDays(), h.campaignId(), h.acknowledgedAt(),
						byRegistration.getOrDefault(h.id(), List.of())))
				.toList();
	}

	/** 단건 조회 — 유저 스코프 없음(실행기가 registrationId만 갖고 백그라운드에서 부른다). */
	public Optional<RegistrationRow> findById(long registrationId) {
		Optional<RegistrationHeader> header = jdbcClient.sql("""
				SELECT id, user_id, requested_at, completed_at, tracking_days, campaign_id, acknowledged_at
				FROM app.monitoring_registrations
				WHERE id = :id
				""")
				.param("id", registrationId)
				.query(RegistrationHeader.class)
				.optional();
		if (header.isEmpty()) {
			return Optional.empty();
		}
		RegistrationHeader h = header.get();
		List<RegistrationEntryRow> entries = entriesByRegistration(List.of(registrationId))
				.getOrDefault(registrationId, List.of());
		return Optional.of(new RegistrationRow(h.id(), h.userId(), h.requestedAt(), h.completedAt(),
				h.trackingDays(), h.campaignId(), h.acknowledgedAt(), entries));
	}

	/**
	 * 본인 소유 행만 확인 처리 — 존재하지 않는 id·타 유저 id는 WHERE 절이 걸러내고, 이미 확인한 행은
	 * acknowledged_at IS NULL 조건이 걸러내 최초 확인 시각을 보존한다(멱등, DigestRepository.markRead와
	 * 동일한 패턴). 빈 리스트는 IN () SQL 오류를 피하려 no-op.
	 */
	public void markAcknowledged(long userId, List<Long> ids) {
		if (ids.isEmpty()) {
			return;
		}
		jdbcClient.sql("""
				UPDATE app.monitoring_registrations
				SET acknowledged_at = now()
				WHERE user_id = :userId AND id IN (:ids) AND acknowledged_at IS NULL
				""")
				.param("userId", userId)
				.param("ids", ids)
				.update();
	}

	/** 미확인 전체 확인 처리 — 응답 창(최근 50건) 제한 없이 유저 전체 대상. */
	public void markAllAcknowledged(long userId) {
		jdbcClient.sql("""
				UPDATE app.monitoring_registrations
				SET acknowledged_at = now()
				WHERE user_id = :userId AND acknowledged_at IS NULL
				""")
				.param("userId", userId)
				.update();
	}

	/**
	 * 취소 시점 정산(트랙 LL §4-2) — 취소된 item에 매달린 pending entry 1건을 canceled로 확정한다.
	 * {@code findEntryByItemId + updateEntryResult} 조합 대신 조건부 UPDATE 한 방으로 하는 이유는
	 * 경합 방어다: 실행기 스레드(processItem)와 취소 요청(V1MonitoringItemUpdateService.cancel)이
	 * 같은 entry를 동시에 만지는 창이 실측으로 확인됐고(운영 registration id=2), 무조건 덮어쓰는
	 * update라면 이미 success로 정산된 entry를 canceled로 되돌릴 수 있다. {@code result = 'pending'}
	 * 조건을 SQL에 박아 원자적으로 막는다 — 이미 정산된 entry는 이 UPDATE의 대상이 아니다.
	 *
	 * @return 정산된 entry가 있으면 그 registration_id(호출부가 뒤이어 markCompletedIfAllSettled를
	 *         돌린다), 이미 정산됐거나 없는 itemId면 빈 값
	 */
	public Optional<Long> settleCanceledByItem(long itemId) {
		return jdbcClient.sql("""
				UPDATE app.monitoring_registration_entries
				SET result = 'canceled', reason_code = 'canceled', reason = :reason
				WHERE item_id = :itemId AND result = 'pending'
				RETURNING registration_id
				""")
				.param("itemId", itemId)
				.param("reason", CANCELED_REASON)
				.query(Long.class)
				.optional();
	}

	/**
	 * 나이 기반 entry 스윕(트랙 LL §4-3) — {@code requested_at} 기준 age 초과인 pending entry를
	 * item의 실제 상태로 확정한다. 나이는 "이제 판정할 때가 됐다"는 트리거일 뿐, 확정 값은 나이가
	 * 아니라 item 상태로 정한다(설계 §2-2 — 나이만 보고 일괄 failed로 밀면 target이 이미 붙어 실제로는
	 * 성공한 건을 실패로 오보하게 된다):
	 * <ul>
	 *   <li>target_id 있음 → success (§2-2 크래시 창: confirmTarget과 updateEntryResult가 별도
	 *       트랜잭션이라 그 사이 프로세스가 죽으면 이 상태로 남는다)</li>
	 *   <li>canceled_at 있음 → canceled (§2-1 취소 경로 누락분의 최종 안전망 — settleCanceledByItem이
	 *       놓쳤거나 아직 배포 전이던 시절의 잔재)</li>
	 *   <li>item 없음(item_id NULL·share-resolve 실패로 행 자체가 안 생겼거나, 삭제돼 FK가 NULL이 된
	 *       경우) 또는 target 미확정 → failed + internal_error</li>
	 * </ul>
	 * CTE로 대상·확정값을 먼저 골라(entries LEFT JOIN items) 같은 UPDATE 문에서 적용한다 — Postgres의
	 * UPDATE ... FROM 절에서 target 테이블 자신을 JOIN ON에 참조할 수 없어 CTE로 분리했다.
	 *
	 * @return 영향받은 registration_id 목록(중복 제거) — 호출부가 각각 markCompletedIfAllSettled를 돌린다
	 */
	public List<Long> settleStaleEntries(Duration age) {
		List<Long> registrationIds = jdbcClient.sql("""
				WITH stale AS (
				    SELECT e.registration_id, e.seq,
				           CASE
				               WHEN i.target_id IS NOT NULL THEN 'success'
				               WHEN i.canceled_at IS NOT NULL THEN 'canceled'
				               ELSE 'failed'
				           END AS new_result,
				           CASE
				               WHEN i.target_id IS NOT NULL THEN NULL
				               WHEN i.canceled_at IS NOT NULL THEN 'canceled'
				               ELSE 'internal_error'
				           END AS new_reason_code,
				           CASE
				               WHEN i.target_id IS NOT NULL THEN NULL
				               WHEN i.canceled_at IS NOT NULL THEN :canceledReason
				               ELSE :internalErrorReason
				           END AS new_reason
				    FROM app.monitoring_registration_entries e
				    JOIN app.monitoring_registrations r ON r.id = e.registration_id
				    LEFT JOIN app.monitoring_items i ON i.id = e.item_id
				    WHERE e.result = 'pending'
				      AND r.requested_at < now() - make_interval(secs => :seconds)
				)
				UPDATE app.monitoring_registration_entries e
				SET result = stale.new_result, reason_code = stale.new_reason_code, reason = stale.new_reason
				FROM stale
				WHERE e.registration_id = stale.registration_id AND e.seq = stale.seq
				RETURNING e.registration_id
				""")
				.param("seconds", (double) age.toMillis() / 1000.0)
				.param("canceledReason", CANCELED_REASON)
				.param("internalErrorReason", STALE_INTERNAL_ERROR_REASON)
				.query(Long.class)
				.list();
		return registrationIds.stream().distinct().toList();
	}

	/** item_id로 소속 entry 역조회 — 실행기의 pending 복구가 registration_id·seq를 모른 채 항목만 갖고 있을 때 쓴다. */
	public Optional<RegistrationEntryRow> findEntryByItemId(long itemId) {
		return jdbcClient.sql("""
				SELECT registration_id, seq, input, kind, result, reason_code, reason, resolved_url, item_id
				FROM app.monitoring_registration_entries
				WHERE item_id = :itemId
				""")
				.param("itemId", itemId)
				.query(RegistrationEntryRow.class)
				.optional();
	}

	public long countByUser(long userId) {
		return jdbcClient.sql("SELECT count(*) FROM app.monitoring_registrations WHERE user_id = :userId")
				.param("userId", userId)
				.query(Long.class)
				.single();
	}

	private Map<Long, List<RegistrationEntryRow>> entriesByRegistration(List<Long> registrationIds) {
		if (registrationIds.isEmpty()) {
			return Map.of();
		}
		List<RegistrationEntryRow> entries = jdbcClient.sql("""
				SELECT registration_id, seq, input, kind, result, reason_code, reason, resolved_url, item_id
				FROM app.monitoring_registration_entries
				WHERE registration_id IN (:ids)
				ORDER BY registration_id ASC, seq ASC
				""")
				.param("ids", registrationIds)
				.query(RegistrationEntryRow.class)
				.list();
		return entries.stream().collect(Collectors.groupingBy(RegistrationEntryRow::registrationId));
	}

	/** entries 없이 헤더만 매핑하기 위한 내부 전용 행 — RecordRowMapper 컬럼 매칭용. */
	private record RegistrationHeader(long id, long userId, OffsetDateTime requestedAt, OffsetDateTime completedAt,
			Integer trackingDays, Long campaignId, OffsetDateTime acknowledgedAt) {
	}
}
