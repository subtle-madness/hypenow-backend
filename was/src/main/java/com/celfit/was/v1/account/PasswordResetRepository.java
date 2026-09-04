package com.celfit.was.v1.account;

import com.celfit.was.auth.UserRepository;
import com.celfit.was.crypto.FieldCipher;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.password_resets 접근 — 이메일당 1행. 재발송 upsert → confirm에서 코드 소모·토큰 기록 →
 * reset에서 claim(DELETE..RETURNING)으로 토큰 1회 소비. 코드·토큰은 SHA-256 해시로만 저장(원문 무저장).
 * confirm·reset 모두 조건부 쿼리로 원자성을 보장한다(동시 요청 중 하나만 통과 — SignupCodeRepository.claim 관용구).
 *
 * <p>읽기 전환(스펙 §전환 2, 09-04) — 행 지목은 전부 {@code email_bidx}, 이메일 값은
 * {@code decrypt(email_enc)}다. upsert의 {@code ON CONFLICT (email)}만 평문을 남겨 둔다:
 * PK가 아직 email이라 그 자리를 email_bidx로 바꾸려면 제약 교체가 필요하고, 그건 평문 컬럼을
 * 걷어내는 PR 3(contract)의 일이다. 평문 UNIQUE와 email_bidx UNIQUE가 같은 1행을 가리키므로
 * 두 키가 공존하는 동안에도 "이메일당 1행" 불변식은 그대로다.
 */
@Repository
public class PasswordResetRepository {

	public record ResetChallenge(String email, String codeHash, OffsetDateTime codeExpiresAt,
			int attempts, String tokenHash, OffsetDateTime tokenExpiresAt) {
	}

	public record ClaimedToken(String email, OffsetDateTime tokenExpiresAt) {
	}

	private final JdbcClient jdbcClient;
	private final FieldCipher fieldCipher;
	private final RowMapper<ResetChallenge> challengeMapper;
	private final RowMapper<ClaimedToken> claimMapper;

	public PasswordResetRepository(JdbcClient jdbcClient, FieldCipher fieldCipher) {
		this.jdbcClient = jdbcClient;
		this.fieldCipher = fieldCipher;
		// 조회는 email_bidx로, 이메일 값은 email_enc 복호화로 — 평문 email 컬럼은 쓰기 전용이다.
		this.challengeMapper = (rs, rowNum) -> new ResetChallenge(
				fieldCipher.decrypt(rs.getString("email_enc")),
				rs.getString("code_hash"),
				rs.getObject("code_expires_at", OffsetDateTime.class),
				rs.getInt("attempts"),
				rs.getString("token_hash"),
				rs.getObject("token_expires_at", OffsetDateTime.class));
		this.claimMapper = (rs, rowNum) -> new ClaimedToken(
				fieldCipher.decrypt(rs.getString("email_enc")),
				rs.getObject("token_expires_at", OffsetDateTime.class));
	}

	/**
	 * 발송 성공 후에만 호출 — 기존 행이 있으면 코드 교체 + attempts·토큰 리셋(마지막 발송만 유효).
	 * email_enc/email_bidx도 INSERT·재발송 UPDATE 양쪽에서 함께 채운다(스펙 §전환 1 이중 쓰기).
	 *
	 * <p><b>평문 email도 반드시 정규화 값으로 쓴다</b>(리뷰 R1 Important). 쓰기 충돌 키는 아직
	 * 평문 PK({@code ON CONFLICT (email)})이고 읽기 키는 정규화 bidx라, 평문만 원문으로 쓰면 두 키가
	 * 갈린다 — 대소문자가 다른 재발송이 ON CONFLICT를 비껴가 새 INSERT가 되고, 그 행의 bidx는
	 * 기존 행과 같아 {@code password_resets_email_bidx_key} UNIQUE 위반(발송 500)이 된다.
	 * 호출부(V1PasswordResetController)가 이미 정규화해 넘기지만, 불변식은 리포지토리가 보장한다.
	 */
	public void upsert(String email, String codeHash, Instant codeExpiresAt) {
		String normalized = UserRepository.normalizeEmail(email);
		jdbcClient.sql("""
				INSERT INTO app.password_resets (email, code_hash, code_expires_at, email_enc, email_bidx)
				VALUES (:email, :codeHash, :codeExpiresAt, :emailEnc, :emailBidx)
				ON CONFLICT (email) DO UPDATE
				SET code_hash = EXCLUDED.code_hash, code_expires_at = EXCLUDED.code_expires_at,
				    attempts = 0, token_hash = NULL, token_expires_at = NULL, created_at = now(),
				    email_enc = EXCLUDED.email_enc, email_bidx = EXCLUDED.email_bidx""")
				.param("email", normalized)
				.param("codeHash", codeHash)
				.param("codeExpiresAt", OffsetDateTime.ofInstant(codeExpiresAt, ZoneOffset.UTC))
				.param("emailEnc", fieldCipher.encrypt(normalized))
				.param("emailBidx", fieldCipher.blindIndex(normalized))
				.update();
	}

	public Optional<ResetChallenge> find(String email) {
		return jdbcClient.sql("""
				SELECT email_enc, code_hash, code_expires_at, attempts, token_hash, token_expires_at
				FROM app.password_resets WHERE email_bidx = :emailBidx""")
				.param("emailBidx", blindIndex(email))
				.query(challengeMapper)
				.optional();
	}

	/** 해시 불일치 오입력 카운트 — 만료·부재는 세지 않는다(서비스 판정 순서 참조). */
	public void incrementAttempts(String email) {
		jdbcClient.sql("UPDATE app.password_resets SET attempts = attempts + 1 WHERE email_bidx = :emailBidx")
				.param("emailBidx", blindIndex(email))
				.update();
	}

	/**
	 * confirm 성공 — 코드 일치 행만 원자적으로 소모(NULL)하고 토큰을 기록한다. WHERE에 code_hash를
	 * 걸어 조건부 UPDATE로 만든 것이 핵심 — 동시 confirm 두 개가 오면 한쪽만 행을 갱신해(영향 0행인
	 * 쪽은 false) 토큰이 두 개 발급되는 레이스를 막는다. 반환 false는 이미 다른 요청이 소모했다는 뜻
	 * (서비스는 사유 비구분 INVALID_VERIFICATION_CODE로 응답).
	 */
	public boolean consumeCodeAndIssueToken(String email, String codeHash, String tokenHash, Instant tokenExpiresAt) {
		return jdbcClient.sql("""
				UPDATE app.password_resets
				SET code_hash = NULL, token_hash = :tokenHash, token_expires_at = :tokenExpiresAt
				WHERE email_bidx = :emailBidx AND code_hash = :codeHash""")
				.param("tokenHash", tokenHash)
				.param("tokenExpiresAt", OffsetDateTime.ofInstant(tokenExpiresAt, ZoneOffset.UTC))
				.param("emailBidx", blindIndex(email))
				.param("codeHash", codeHash)
				.update() > 0;
	}

	/**
	 * 토큰 원자적 소비 — DELETE..RETURNING 한 방이 정본(SignupCodeRepository.claim 관용구). 동시에
	 * 같은 토큰으로 reset이 두 번 오면 한쪽만 행을 얻고(claim 성공), 다른 쪽은 빈 Optional을 받는다
	 * (find 후 별도 delete로는 두 요청이 둘 다 read에 성공해 이중 소비가 가능했다 — TOCTOU).
	 */
	public Optional<ClaimedToken> claimByTokenHash(String tokenHash) {
		return jdbcClient.sql("""
				DELETE FROM app.password_resets WHERE token_hash = :tokenHash
				RETURNING email_enc, token_expires_at""")
				.param("tokenHash", tokenHash)
				.query(claimMapper)
				.optional();
	}

	/** 조회 키 — 저장 시와 같은 정규화 규칙(UserRepository 정본)을 통과한 값의 블라인드 인덱스. */
	private String blindIndex(String email) {
		return fieldCipher.blindIndex(UserRepository.normalizeEmail(email));
	}
}
