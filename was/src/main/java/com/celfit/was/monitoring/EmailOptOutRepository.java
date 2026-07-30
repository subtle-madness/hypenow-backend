package com.celfit.was.monitoring;

import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.monitoring_email_opt_outs CRUD(v3 V15, 스펙 6.33). 행 없음=이메일 on(기본), 행 있음=옵트아웃.
 * event_type은 DB CHECK 제약(4종)이 최종 방어선이라 여기서는 값 검증을 하지 않는다 —
 * 미지 이벤트 유형은 서비스 계층(NotificationSettingsService)이 400으로 먼저 막는다.
 */
@Repository
public class EmailOptOutRepository {

	private final JdbcClient jdbcClient;

	public EmailOptOutRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 유저의 옵트아웃(이메일 off) event_type 집합. */
	public Set<String> findOptOuts(long userId) {
		return Set.copyOf(jdbcClient.sql("""
				SELECT event_type FROM app.monitoring_email_opt_outs WHERE user_id = :userId
				""")
				.param("userId", userId)
				.query(String.class)
				.list());
	}

	/** 멱등 — 이미 옵트아웃 상태면 그대로 둔다. */
	public void optOut(long userId, String eventType) {
		jdbcClient.sql("""
				INSERT INTO app.monitoring_email_opt_outs (user_id, event_type) VALUES (:userId, :eventType)
				ON CONFLICT DO NOTHING
				""")
				.param("userId", userId)
				.param("eventType", eventType)
				.update();
	}

	/** 멱등 — 행이 없어도(이미 on) 에러 없이 통과. */
	public void optIn(long userId, String eventType) {
		jdbcClient.sql("""
				DELETE FROM app.monitoring_email_opt_outs WHERE user_id = :userId AND event_type = :eventType
				""")
				.param("userId", userId)
				.param("eventType", eventType)
				.update();
	}
}
