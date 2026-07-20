package com.celfit.was.admin;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 관리자 가입 코드 사용 현황 조회(설계 2026-07-19) — app 스키마만 읽는다(was 경계).
 * signup_codes 전체를 users와 LEFT JOIN해 소진(누가 썼는지)·미소진 코드를 한 번에 반환한다.
 */
@Repository
public class AdminSignupRepository {

	private final JdbcClient jdbcClient;

	public AdminSignupRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public List<SignupUsageRow> findAll() {
		return jdbcClient.sql("""
				SELECT sc.code, sc.channel, u.email, sc.used_by AS user_id, sc.used_at
				FROM app.signup_codes sc
				LEFT JOIN app.users u ON u.id = sc.used_by
				ORDER BY sc.used_at DESC NULLS LAST, sc.code""")
				.query(SignupUsageRow.class)
				.list();
	}
}
