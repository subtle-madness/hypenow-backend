package com.celfit.was.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 가입 코드 단건 삽입(설계 2026-07-20) — app 스키마만 씀(was 경계).
 * ON CONFLICT (code) DO NOTHING이라 반환 1=신규, 0=이미 존재(소진분 포함, 부활 안 함). 트랜잭션은 서비스 소유.
 */
@Repository
public class AdminSignupCodeRepository {

	private final JdbcClient jdbcClient;

	public AdminSignupCodeRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public int insert(String code, String channel) {
		return jdbcClient.sql("""
				INSERT INTO app.signup_codes (code, channel) VALUES (:code, :channel)
				ON CONFLICT (code) DO NOTHING""")
				.param("code", code)
				.param("channel", channel)
				.update();
	}
}
