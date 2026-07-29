package com.celfit.was.v1.account;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 가입 시도 이벤트 기록(app.signup_events, 2026-07-29) — 요청 1건당 1행, 내부 진행 단계까지 detail로 남긴다.
 * fire-and-forget: 기록 실패가 가입 흐름을 깨면 안 되므로 예외는 삼키고 warn만 남긴다(gate_events 선례).
 */
@Component
public class SignupEventRecorder {

	public static final String OUTCOME_OK = "ok";

	private static final Logger log = LoggerFactory.getLogger(SignupEventRecorder.class);

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public SignupEventRecorder(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	public void record(String email, String outcome, String ip, Map<String, Object> detail) {
		try {
			jdbcClient.sql("""
					INSERT INTO app.signup_events (email, outcome, ip, detail)
					VALUES (:email, :outcome, :ip, CAST(:detail AS jsonb))
					""")
					.param("email", email == null ? "" : email)
					.param("outcome", outcome)
					.param("ip", ip)
					.param("detail", objectMapper.writeValueAsString(detail))
					.update();
		} catch (RuntimeException e) {
			log.warn("가입 이벤트 기록 실패(무시) — outcome={}", outcome, e);
		}
	}
}
