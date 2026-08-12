package com.celfit.was.v1.account;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.password_resets 접근 — 이메일당 1행. 재발송 upsert → confirm에서 코드 소모·토큰 기록 →
 * reset에서 행 삭제(토큰 1회 소비). 코드·토큰은 SHA-256 해시로만 저장(원문 무저장).
 */
@Repository
public class PasswordResetRepository {

	public record ResetChallenge(String email, String codeHash, OffsetDateTime codeExpiresAt,
			int attempts, String tokenHash, OffsetDateTime tokenExpiresAt) {
	}

	private final JdbcClient jdbcClient;

	public PasswordResetRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 발송 성공 후에만 호출 — 기존 행이 있으면 코드 교체 + attempts·토큰 리셋(마지막 발송만 유효). */
	public void upsert(String email, String codeHash, Instant codeExpiresAt) {
		jdbcClient.sql("""
				INSERT INTO app.password_resets (email, code_hash, code_expires_at)
				VALUES (:email, :codeHash, :codeExpiresAt)
				ON CONFLICT (email) DO UPDATE
				SET code_hash = EXCLUDED.code_hash, code_expires_at = EXCLUDED.code_expires_at,
				    attempts = 0, token_hash = NULL, token_expires_at = NULL, created_at = now()""")
				.param("email", email)
				.param("codeHash", codeHash)
				.param("codeExpiresAt", OffsetDateTime.ofInstant(codeExpiresAt, ZoneOffset.UTC))
				.update();
	}

	public Optional<ResetChallenge> find(String email) {
		return jdbcClient.sql("""
				SELECT email, code_hash, code_expires_at, attempts, token_hash, token_expires_at
				FROM app.password_resets WHERE email = :email""")
				.param("email", email)
				.query(ResetChallenge.class)
				.optional();
	}

	public Optional<ResetChallenge> findByTokenHash(String tokenHash) {
		return jdbcClient.sql("""
				SELECT email, code_hash, code_expires_at, attempts, token_hash, token_expires_at
				FROM app.password_resets WHERE token_hash = :tokenHash""")
				.param("tokenHash", tokenHash)
				.query(ResetChallenge.class)
				.optional();
	}

	/** 해시 불일치 오입력 카운트 — 만료·부재는 세지 않는다(서비스 판정 순서 참조). */
	public void incrementAttempts(String email) {
		jdbcClient.sql("UPDATE app.password_resets SET attempts = attempts + 1 WHERE email = :email")
				.param("email", email)
				.update();
	}

	/** confirm 성공 — 코드를 소모(NULL)하고 토큰 해시를 기록한다(같은 코드 재검증 차단). */
	public void consumeCodeAndIssueToken(String email, String tokenHash, Instant tokenExpiresAt) {
		jdbcClient.sql("""
				UPDATE app.password_resets
				SET code_hash = NULL, token_hash = :tokenHash, token_expires_at = :tokenExpiresAt
				WHERE email = :email""")
				.param("tokenHash", tokenHash)
				.param("tokenExpiresAt", OffsetDateTime.ofInstant(tokenExpiresAt, ZoneOffset.UTC))
				.param("email", email)
				.update();
	}

	/** reset 성공(토큰 1회 소비) 또는 만료 행 청소. */
	public void delete(String email) {
		jdbcClient.sql("DELETE FROM app.password_resets WHERE email = :email")
				.param("email", email)
				.update();
	}
}
