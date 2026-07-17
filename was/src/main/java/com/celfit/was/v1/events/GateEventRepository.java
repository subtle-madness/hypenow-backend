package com.celfit.was.v1.events;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.gate_events 기록 전용 리포지토리(스펙 6.19). payloadJson은 텍스트로 받아 CAST로 jsonb 저장한다 —
 * was에 jsonb write 선례가 없어 CAST 방식을 택했다(PostgreSQL이 캐스트 시 JSON 유효성도 검증한다).
 */
@Repository
public class GateEventRepository {

	private final JdbcClient jdbcClient;

	public GateEventRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** userId null이면 익명, payloadJson null이면 payload는 SQL NULL로 저장된다. */
	public void insert(Long userId, String eventType, String payloadJson) {
		jdbcClient.sql("""
				INSERT INTO app.gate_events (user_id, event_type, payload)
				VALUES (:userId, :eventType, CAST(:payload AS jsonb))
				""")
				.param("userId", userId)
				.param("eventType", eventType)
				.param("payload", payloadJson)
				.update();
	}
}
