package com.celfit.was.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 가입 코드 단건 삽입(설계 2026-07-20) — app 스키마만 씀(was 경계).
 * ON CONFLICT (code) DO NOTHING이라 반환 1=신규, 0=이미 존재(소진분 포함, 부활 안 함). 트랜잭션은 서비스 소유.
 * isSuper(설계 2026-08-04)는 배치 전체에 동일하게 적용된다 — 단, 이미 존재하는 코드는 ON CONFLICT로
 * 조용히 스킵되므로 isSuper 값도 함께 무시된다(재적재로 일반 코드를 super로 승격 못 함).
 * 승격 경로는 PATCH /admin/signup-codes/{code}(후속 태스크에서 확장 예정).
 */
@Repository
public class AdminSignupCodeRepository {

	private final JdbcClient jdbcClient;

	public AdminSignupCodeRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public int insert(String code, String channel, boolean isSuper) {
		return jdbcClient.sql("""
				INSERT INTO app.signup_codes (code, channel, is_super) VALUES (:code, :channel, :isSuper)
				ON CONFLICT (code) DO NOTHING""")
				.param("code", code)
				.param("channel", channel)
				.param("isSuper", isSuper)
				.update();
	}
}
