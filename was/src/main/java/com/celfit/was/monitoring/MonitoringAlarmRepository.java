package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 알람 설정·발송 상태(app 스키마 — 스펙 2026-07-29 §2). 옵트아웃은 "행 없음 = on(기본)" —
 * 설정 토글 API는 프론트 /v1 작업 때 붙고, 지금은 크론이 읽기만 한다.
 * 모니터링 비활성이어도 무해한 app 테이블 접근이라 항상 활성.
 */
@Repository
public class MonitoringAlarmRepository {

	private final JdbcClient jdbcClient;

	public MonitoringAlarmRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Set<Long> optedOutUserIds(String eventType, Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(jdbcClient.sql("""
				SELECT user_id FROM app.monitoring_email_opt_outs
				WHERE event_type = :eventType AND user_id IN (:userIds)
				""")
				.param("eventType", eventType)
				.param("userIds", userIds)
				.query(Long.class)
				.list());
	}

	/** 이벤트 워터마크 — V15 시드로 행이 항상 있다(없으면 마이그레이션 누락이라 예외가 맞다). */
	public OffsetDateTime watermark(String eventType) {
		return jdbcClient.sql("""
				SELECT last_notified_at FROM app.monitoring_alarm_state WHERE event_type = :eventType
				""")
				.param("eventType", eventType)
				.query(OffsetDateTime.class)
				.single();
	}

	/** 전진만 허용(후퇴 방지 가드) — 과거 값으로 호출돼도 무해. */
	public void advanceWatermark(String eventType, OffsetDateTime to) {
		jdbcClient.sql("""
				UPDATE app.monitoring_alarm_state SET last_notified_at = :to
				WHERE event_type = :eventType AND last_notified_at < :to
				""")
				.param("to", to)
				.param("eventType", eventType)
				.update();
	}

	public Map<Long, String> emailsByUserIds(Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return jdbcClient.sql("SELECT id, email FROM app.users WHERE id IN (:ids)")
				.param("ids", userIds)
				.query(UserEmail.class)
				.list()
				.stream()
				.collect(Collectors.toMap(UserEmail::id, UserEmail::email));
	}

	record UserEmail(long id, String email) {
	}
}
