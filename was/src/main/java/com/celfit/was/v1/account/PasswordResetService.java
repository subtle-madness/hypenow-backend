package com.celfit.was.v1.account;

import com.celfit.was.mail.MailSender;
import com.celfit.was.v1.common.V1ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * 비밀번호 재설정(프론트 요청 2026-08-12) — 6자리 코드 발송·확인·1회용 토큰 발급·소비.
 * 옛 이메일 인증(설계 2026-07-18, cc14c717에서 철거)의 판정 순서를 이식했다.
 * 주 방어선은 코드 TTL 5분 + 오입력 5회 + 레이트리밋(컨트롤러). confirm·reset 실패는
 * 사유 비구분 단일 응답(부재·만료·시도 초과·불일치·재사용 동일 — 열거 방지).
 * 토큰은 저엔트로피 코드(6자리)를 confirm 시점에 소모하고 교환해 주는 고엔트로피
 * 1회용 자격(256비트, 해시 저장) — 코드 추측 공격 표면을 코드 TTL 안으로 좁힌다.
 */
@Service
public class PasswordResetService {

	static final Duration CODE_TTL = Duration.ofMinutes(5);
	static final Duration TOKEN_TTL = Duration.ofMinutes(10);
	static final int MAX_ATTEMPTS = 5;

	public record IssuedToken(String resetToken, int expiresIn) {
	}

	private final PasswordResetRepository repository;
	private final MailSender mailSender;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	public PasswordResetService(PasswordResetRepository repository, MailSender mailSender, Clock clock) {
		this.repository = repository;
		this.mailSender = mailSender;
		this.clock = clock;
	}

	/** 코드 생성→발송→저장. 발송 성공 후에만 저장(실패했는데 코드가 유효해지는 상태 방지). MailSendException은 컨트롤러가 502로 변환. */
	public void sendCode(String email) {
		String code = "%06d".formatted(random.nextInt(1_000_000));
		mailSender.send(email, "[hypenow] 비밀번호 재설정 인증번호",
				"""
				인증번호: %s

				5분 안에 재설정 화면에 입력해 주세요.
				본인이 요청하지 않았다면 이 메일을 무시하세요.""".formatted(code));
		repository.upsert(email, sha256(code), clock.instant().plus(CODE_TTL));
	}

	/**
	 * 판정 순서: 행 존재 → 코드 미소모·시도 한도·만료 → 해시 일치(불일치만 attempts 증가).
	 * 성공 시 코드를 소모하고 토큰 원문을 반환한다 — 원문은 이 응답이 유일한 노출(DB는 해시만).
	 */
	public IssuedToken confirm(String email, String code) {
		PasswordResetRepository.ResetChallenge row = repository.find(email)
				.orElseThrow(PasswordResetService::invalidCode);
		if (row.codeHash() == null || row.attempts() >= MAX_ATTEMPTS
				|| clock.instant().isAfter(row.codeExpiresAt().toInstant())) {
			throw invalidCode();
		}
		if (code == null || !row.codeHash().equals(sha256(code.trim()))) {
			repository.incrementAttempts(email);
			throw invalidCode();
		}
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = "prt_" + HexFormat.of().formatHex(bytes);
		repository.consumeCodeAndIssueToken(email, sha256(token), clock.instant().plus(TOKEN_TTL));
		return new IssuedToken(token, (int) TOKEN_TTL.toSeconds());
	}

	/**
	 * 토큰 검증 → 즉시 소비(행 삭제) → 소유 이메일 반환. 소비를 비밀번호 변경보다 먼저 해
	 * 어떤 실패 경로에서도 토큰이 두 번 쓰일 수 없다(중간 크래시는 유저가 처음부터 재진행).
	 */
	public String consumeToken(String resetToken) {
		if (resetToken == null || resetToken.isBlank()) {
			throw invalidToken();
		}
		PasswordResetRepository.ResetChallenge row = repository.findByTokenHash(sha256(resetToken.trim()))
				.orElseThrow(PasswordResetService::invalidToken);
		repository.delete(row.email());
		if (row.tokenExpiresAt() == null || clock.instant().isAfter(row.tokenExpiresAt().toInstant())) {
			throw invalidToken();
		}
		return row.email();
	}

	private static V1ApiException invalidCode() {
		return V1ApiException.badRequest("INVALID_VERIFICATION_CODE", "인증번호가 올바르지 않거나 만료됐어요.");
	}

	private static V1ApiException invalidToken() {
		return V1ApiException.badRequest("INVALID_RESET_TOKEN", "인증 시간이 만료됐어요. 처음부터 다시 진행해 주세요.");
	}

	static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 미지원 JVM", e);
		}
	}
}
