package com.celfit.was.v1.account;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 배치 1회용 가입 코드(app.signup_codes, 설계 2026-07-19) — 코드는 trim 후 정확 일치.
 * 소진은 claim의 조건부 UPDATE 한 방이 정본(원자적) — isUsable은 UX용 빠른 실패일 뿐 선점을 보장하지 않는다.
 */
@Repository
public class SignupCodeRepository {

	private final JdbcClient jdbcClient;

	public SignupCodeRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 존재하며 미사용이면 true — 사전 검증(/signup-code/verify)과 가입 초입의 빠른 실패용. 소진 판정은 used_at 기준. */
	public boolean isUsable(String code) {
		String normalized = normalize(code);
		if (normalized.isEmpty()) {
			return false;
		}
		return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM app.signup_codes WHERE code = :code AND used_at IS NULL)")
				.param("code", normalized)
				.query(Boolean.class)
				.single();
	}

	/** 원자 선점(used_at 스탬프) — 이미 소진됐거나 없는 코드면 false. 가입 트랜잭션 안에서 호출할 것. */
	public boolean claim(String code, long userId) {
		String normalized = normalize(code);
		if (normalized.isEmpty()) {
			return false;
		}
		int updated = jdbcClient.sql("""
				UPDATE app.signup_codes SET used_by = :userId, used_at = now()
				WHERE code = :code AND used_at IS NULL""")
				.param("userId", userId)
				.param("code", normalized)
				.update();
		return updated == 1;
	}

	/**
	 * 어드민 유저 상세(설계 2026-08-01 §4 GET /v1/admin/users/{id})의 signupCode 역참조 —
	 * used_by는 코드 소진 시 1회만 세팅되므로(가입 트랜잭션) 유저당 최대 1건.
	 */
	public Optional<String> findCodeByUsedBy(long userId) {
		return jdbcClient.sql("SELECT code FROM app.signup_codes WHERE used_by = :userId")
				.param("userId", userId)
				.query(String.class)
				.optional();
	}

	private static String normalize(String code) {
		return code == null ? "" : code.trim();
	}
}
