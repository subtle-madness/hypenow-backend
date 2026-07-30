package com.celfit.was.monitoring;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.monitoring_email_opt_outs CRUD(V15, 스펙 6.33). 행 없음=이메일 on(기본), 행 있음=옵트아웃.
 * 테이블·CHECK 제약의 어휘 정본은 monitoring AlarmEventType(대문자) — 이 리포지토리가 프론트 소문자
 * 어휘(메서드 시그니처)와 저장 대문자 어휘(SQL) 경계에서 변환한다({@link MonitoringEventTypes}).
 * 미지 이벤트 유형은 서비스 계층(NotificationSettingsService)이 400으로 먼저 막지만, 매핑 자체도
 * 미지 값이면 예외를 던진다(2차 방어선).
 */
@Repository
public class EmailOptOutRepository {

	private final JdbcClient jdbcClient;

	public EmailOptOutRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 유저의 옵트아웃(이메일 off) event_type 집합 — 프론트 소문자 어휘로 반환. */
	public Set<String> findOptOuts(long userId) {
		return jdbcClient.sql("""
				SELECT event_type FROM app.monitoring_email_opt_outs WHERE user_id = :userId
				""")
				.param("userId", userId)
				.query(String.class)
				.list()
				.stream()
				.map(MonitoringEventTypes::toFront)
				.collect(Collectors.toUnmodifiableSet());
	}

	/** 멱등 — 이미 옵트아웃 상태면 그대로 둔다. eventType은 프론트 소문자 어휘. */
	public void optOut(long userId, String eventType) {
		jdbcClient.sql("""
				INSERT INTO app.monitoring_email_opt_outs (user_id, event_type) VALUES (:userId, :eventType)
				ON CONFLICT DO NOTHING
				""")
				.param("userId", userId)
				.param("eventType", MonitoringEventTypes.toStorage(eventType))
				.update();
	}

	/** 멱등 — 행이 없어도(이미 on) 에러 없이 통과. eventType은 프론트 소문자 어휘. */
	public void optIn(long userId, String eventType) {
		jdbcClient.sql("""
				DELETE FROM app.monitoring_email_opt_outs WHERE user_id = :userId AND event_type = :eventType
				""")
				.param("userId", userId)
				.param("eventType", MonitoringEventTypes.toStorage(eventType))
				.update();
	}
}
