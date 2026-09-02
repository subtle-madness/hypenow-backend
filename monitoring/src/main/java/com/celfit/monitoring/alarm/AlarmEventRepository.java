package com.celfit.monitoring.alarm;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * alarm_event 테이블 접점 — <b>적재 전용</b>이다.
 *
 * <p>2026-08-27 주간 개편으로 발송 대상 조회(findDue)와 행 단위 상태 종결(updateStatus)은
 * 소비자가 사라졌다: 메일 발송이 was의 주간 리포트로 옮겨갔고, was는 이 원장을
 * {@code occurred_at} 기간 조회로만 읽는다(설계 §4·§6). {@code email_status}·
 * {@code email_attempts}·{@code dispatch_after} 컬럼은 expand-contract상 남겨 둔다.
 */
@Repository
public class AlarmEventRepository {

	private final JdbcTemplate db;

	public AlarmEventRepository(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * occurredAt을 애플리케이션에서 명시해 싣는다(테이블 DEFAULT now()에 맡기지 않는다) —
	 * 주간 창 판정이 이 값의 KST 주에 달려 있어, DB 클록과 JVM 클록이 갈리면 경계 이벤트가
	 * 어느 주에 속하는지 재현 불가능해진다.
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
}
