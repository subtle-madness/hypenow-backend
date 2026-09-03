package com.celfit.was.v1.account;

import com.celfit.was.auth.UserRepository;
import com.celfit.was.crypto.FieldCipher;
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
	private final FieldCipher fieldCipher;

	public SignupEventRecorder(JdbcClient jdbcClient, ObjectMapper objectMapper, FieldCipher fieldCipher) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
		this.fieldCipher = fieldCipher;
	}

	/**
	 * email_enc는 email 평문 컬럼과 동일 의미론으로 원문(정규화 없이, null은 "")을 암호화한다.
	 * email_bidx만 조회 키로 정규화(UserRepository.normalizeEmail) 적용값을 쓴다(스펙 §전환 1).
	 */
	public void record(String email, String outcome, String ip, Map<String, Object> detail) {
		try {
			String storedEmail = email == null ? "" : email;
			String normalized = UserRepository.normalizeEmail(storedEmail);
			jdbcClient.sql("""
					INSERT INTO app.signup_events (email, outcome, ip, detail, email_enc, email_bidx, ip_enc)
					VALUES (:email, :outcome, :ip, CAST(:detail AS jsonb), :emailEnc, :emailBidx, :ipEnc)
					""")
					.param("email", storedEmail)
					.param("outcome", outcome)
					.param("ip", ip)
					.param("detail", objectMapper.writeValueAsString(detail))
					.param("emailEnc", fieldCipher.encrypt(storedEmail))
					.param("emailBidx", fieldCipher.blindIndex(normalized))
					.param("ipEnc", fieldCipher.encrypt(ip))
					.update();
		} catch (RuntimeException e) {
			log.warn("가입 이벤트 기록 실패(무시) — outcome={}", outcome, e);
		}
	}
}
