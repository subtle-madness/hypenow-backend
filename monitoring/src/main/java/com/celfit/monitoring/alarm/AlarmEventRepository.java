package com.celfit.monitoring.alarm;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** alarm_event 테이블 접점 — 적재·발송 대상 조회·행 단위 상태 종결. */
@Repository
public class AlarmEventRepository {

	private static final RowMapper<AlarmEvent> ROW = (rs, i) -> new AlarmEvent(
			rs.getLong("id"), rs.getLong("target_id"), rs.getLong("user_id"),
			AlarmEventType.valueOf(rs.getString("event_type")), rs.getString("payload"),
			rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("dispatch_after").toInstant(),
			AlarmEmailStatus.valueOf(rs.getString("email_status")),
			rs.getTimestamp("email_sent_at") == null ? null : rs.getTimestamp("email_sent_at").toInstant());

	private final JdbcTemplate db;

	public AlarmEventRepository(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * occurredAt을 애플리케이션에서 명시해 싣는다(테이블 DEFAULT now()에 맡기지 않는다) — 즉시 레인은
	 * dispatch_after가 occurred_at과 **같아야** 하는데, 하나는 DB 클록(now())·하나는 JVM 클록으로
	 * 따로 읽으면 두 클록이 미묘하게 어긋나 같은 요청인데도 값이 갈린다.
	 */
	public long insert(long targetId, long userId, AlarmEventType type, String payloadJson,
			Instant occurredAt, Instant dispatchAfter) {
		return db.queryForObject("""
				INSERT INTO alarm_event (target_id, user_id, event_type, payload, occurred_at, dispatch_after)
				VALUES (?, ?, ?, ?::jsonb, ?, ?)
				RETURNING id""",
				Long.class, targetId, userId, type.name(), payloadJson,
				Timestamp.from(occurredAt), Timestamp.from(dispatchAfter));
	}

	/**
	 * 발송 대상 — 아직 종결되지 않았고 발송 시각이 지난 행.
	 * FAILED를 함께 집는 게 행 단위 재시도다(스펙 §3-1): 다음 틱이 그 행만 다시 보낸다.
	 * 상한(email_attempts)을 WHERE에 두는 이유: 별도 "포기" 상태 전이를 만들지 않아도
	 * 상한에 닿은 행이 조회에서 자연히 빠진다 — FAILED + attempts>=상한이 곧 종결이다.
	 * 유저별로 묶어 1통으로 합치므로 user_id 우선 정렬로 돌려준다.
	 */
	public List<AlarmEvent> findDue(Instant now, int maxAttempts) {
		return db.query("""
				SELECT id, target_id, user_id, event_type, payload::text AS payload, occurred_at,
				       dispatch_after, email_status, email_sent_at
				FROM alarm_event
				WHERE email_status IN ('PENDING', 'FAILED') AND dispatch_after <= ?
				  AND email_attempts < ?
				ORDER BY user_id, occurred_at, id""",
				ROW, Timestamp.from(now), maxAttempts);
	}

	/**
	 * 행 단위 종결 + 시도 횟수 증가.
	 * sentAt은 SENT일 때만 채운다 — 스킵·실패에 발송 시각을 남기면 이력이 거짓말을 한다.
	 * attempts는 성공·실패·스킵을 가리지 않고 올린다: "이 행을 몇 번 집었나"가 상한의 기준이고,
	 * 실패에서만 올리면 성공/실패가 번갈아 나는 경로에서 상한이 영영 안 찬다.
	 */
	public void updateStatus(Collection<Long> ids, AlarmEmailStatus status, Instant sentAt) {
		if (ids.isEmpty()) {
			return;
		}
		db.batchUpdate("""
				UPDATE alarm_event
				SET email_status=?, email_sent_at=?, email_attempts = email_attempts + 1
				WHERE id=?""",
				ids.stream().map(id -> new Object[] {
						status.name(), sentAt == null ? null : Timestamp.from(sentAt), id }).toList());
	}
}
