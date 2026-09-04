package com.celfit.was.admin;

import com.celfit.was.crypto.FieldCipher;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 관리자 가입 코드 사용 현황 조회(설계 2026-07-19) — app 스키마만 읽는다(was 경계).
 * signup_codes 전체를 users와 LEFT JOIN해 소진(누가 썼는지)·미소진 코드를 한 번에 반환한다.
 * 이메일은 {@code decrypt(u.email_enc)}로 읽는다(스펙 §전환 2, 09-04 읽기 전환) — LEFT JOIN이라
 * 미소진·탈퇴 코드는 email_enc가 NULL이고, {@code FieldCipher.decrypt(null)}이 null을 그대로
 * 돌려줘 기존 "email null" 의미론이 유지된다.
 */
@Repository
public class AdminSignupRepository {

	private final JdbcClient jdbcClient;
	private final RowMapper<SignupUsageRow> rowMapper;

	public AdminSignupRepository(JdbcClient jdbcClient, FieldCipher fieldCipher) {
		this.jdbcClient = jdbcClient;
		this.rowMapper = (rs, rowNum) -> new SignupUsageRow(
				rs.getString("code"),
				rs.getString("channel"),
				fieldCipher.decrypt(rs.getString("email_enc")),
				rs.getObject("user_id", Long.class),
				rs.getObject("used_at", OffsetDateTime.class),
				rs.getBoolean("is_sent"),
				rs.getBoolean("is_super"));
	}

	public List<SignupUsageRow> findAll() {
		return jdbcClient.sql("""
				SELECT sc.code, sc.channel, u.email_enc, sc.used_by AS user_id, sc.used_at, sc.is_sent, sc.is_super
				FROM app.signup_codes sc
				LEFT JOIN app.users u ON u.id = sc.used_by
				ORDER BY sc.used_at DESC NULLS LAST, sc.code""")
				.query(rowMapper)
				.list();
	}

	/**
	 * 부분 갱신(설계 2026-08-04) — null인 필드는 기존 값 유지(COALESCE), 빈 결과면 코드 없음(호출부가 404 판정).
	 * CAST 명시는 untyped null 파라미터의 타입 추론 실패(could not determine data type) 방지.
	 */
	public Optional<SignupCodePatchResponse> updatePartial(String code, Boolean isSent, Boolean isSuper) {
		return jdbcClient.sql("""
				UPDATE app.signup_codes
				SET is_sent = COALESCE(CAST(:isSent AS boolean), is_sent),
				    is_super = COALESCE(CAST(:isSuper AS boolean), is_super)
				WHERE code = :code
				RETURNING code, is_sent, is_super""")
				.param("isSent", isSent)
				.param("isSuper", isSuper)
				.param("code", code)
				.query(SignupCodePatchResponse.class)
				.optional();
	}
}
