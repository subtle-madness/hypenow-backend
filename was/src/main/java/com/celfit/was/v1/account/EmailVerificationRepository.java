package com.celfit.was.v1.account;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** app.email_verifications 접근 — 이메일당 1행. 재발송 upsert·인증 마킹·가입 시 소비(삭제). */
@Repository
public class EmailVerificationRepository {

	public record Verification(String email, String codeHash, Instant codeExpiresAt,
			int attempts, Instant verifiedAt) {
	}

	private final JdbcClient jdbcClient;

	public EmailVerificationRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 발송 성공 후에만 호출 — 기존 행이 있으면 코드 교체 + attempts·verified_at 리셋(마지막 발송만 유효). */
	public void upsert(String email, String codeHash, Instant codeExpiresAt) {
		jdbcClient.sql("""
				INSERT INTO app.email_verifications (email, code_hash, code_expires_at)
				VALUES (:email, :codeHash, :codeExpiresAt)
				ON CONFLICT (email) DO UPDATE
				SET code_hash = EXCLUDED.code_hash, code_expires_at = EXCLUDED.code_expires_at,
				    attempts = 0, verified_at = NULL, created_at = now()""")
				.param("email", email)
				.param("codeHash", codeHash)
				.param("codeExpiresAt", OffsetDateTime.ofInstant(codeExpiresAt, ZoneOffset.UTC))
				.update();
	}

	public Optional<Verification> find(String email) {
		return jdbcClient.sql("""
				SELECT email, code_hash, code_expires_at, attempts, verified_at
				FROM app.email_verifications WHERE email = :email""")
				.param("email", email)
				.query((rs, rowNum) -> new Verification(
						rs.getString("email"),
						rs.getString("code_hash"),
						rs.getTimestamp("code_expires_at").toInstant(),
						rs.getInt("attempts"),
						rs.getTimestamp("verified_at") == null ? null : rs.getTimestamp("verified_at").toInstant()))
				.optional();
	}

	/** 해시 불일치 오입력 카운트 — 만료·부재는 세지 않는다(서비스 판정 순서 참조). */
	public void incrementAttempts(String email) {
		jdbcClient.sql("UPDATE app.email_verifications SET attempts = attempts + 1 WHERE email = :email")
				.param("email", email)
				.update();
	}

	public void markVerified(String email, Instant verifiedAt) {
		jdbcClient.sql("UPDATE app.email_verifications SET verified_at = :verifiedAt WHERE email = :email")
				.param("verifiedAt", OffsetDateTime.ofInstant(verifiedAt, ZoneOffset.UTC))
				.param("email", email)
				.update();
	}

	/** 가입 성공 직후 1회 소비 — 잔존해도 verified 30분 만료로 무해. */
	public void delete(String email) {
		jdbcClient.sql("DELETE FROM app.email_verifications WHERE email = :email")
				.param("email", email)
				.update();
	}
}
