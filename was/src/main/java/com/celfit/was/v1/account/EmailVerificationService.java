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
 * 이메일 소유권 인증(설계 2026-07-18) — 6자리 코드 발송·확인·가입 게이트.
 * 주 방어선은 TTL 10분 + 오입력 5회 + 레이트리밋(컨트롤러). confirm 실패는 사유 비구분
 * 400 INVALID_CODE(부재·만료·시도 초과·불일치 동일 응답 — 열거 방지).
 */
@Service
public class EmailVerificationService {

	static final Duration CODE_TTL = Duration.ofMinutes(10);
	static final Duration VERIFIED_TTL = Duration.ofMinutes(30);
	static final int MAX_ATTEMPTS = 5;

	private final EmailVerificationRepository repository;
	private final MailSender mailSender;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	public EmailVerificationService(EmailVerificationRepository repository, MailSender mailSender, Clock clock) {
		this.repository = repository;
		this.mailSender = mailSender;
		this.clock = clock;
	}

	/** 코드 생성→발송→저장. 발송 성공 후에만 저장(실패했는데 코드가 유효해지는 상태 방지). MailSendException은 컨트롤러가 502로 변환. */
	public void sendCode(String email) {
		String code = "%06d".formatted(random.nextInt(1_000_000));
		mailSender.send(email, "[hypenow] 이메일 인증 코드",
				"인증 코드: %s%n%n10분 안에 가입 화면에 입력해 주세요.".formatted(code));
		repository.upsert(email, sha256(code), clock.instant().plus(CODE_TTL));
	}

	/** 판정 순서: 행 존재 → 시도 한도 → 만료 → 해시 일치. 해시 불일치만 attempts를 올린다(만료·부재는 카운트 무의미). */
	public void confirm(String email, String code) {
		EmailVerificationRepository.Verification row = repository.find(email)
				.orElseThrow(EmailVerificationService::invalidCode);
		if (row.attempts() >= MAX_ATTEMPTS || clock.instant().isAfter(row.codeExpiresAt().toInstant())) {
			throw invalidCode();
		}
		if (code == null || !row.codeHash().equals(sha256(code.trim()))) {
			repository.incrementAttempts(email);
			throw invalidCode();
		}
		repository.markVerified(email, clock.instant());
	}

	/** 가입 직전 게이트 — verified_at 존재 + 30분 이내. 아니면 403(재발송→재확인으로 복구). */
	public void requireVerified(String email) {
		boolean verified = repository.find(email)
				.map(EmailVerificationRepository.Verification::verifiedAt)
				.map(at -> !clock.instant().isAfter(at.toInstant().plus(VERIFIED_TTL)))
				.orElse(false);
		if (!verified) {
			throw V1ApiException.forbidden("EMAIL_NOT_VERIFIED", "이메일 인증을 먼저 완료해 주세요.");
		}
	}

	/** 가입 성공 직후 1회 소비 — 잔존해도 verified 30분 만료로 무해(원자성 불요). */
	public void consume(String email) {
		repository.delete(email);
	}

	private static V1ApiException invalidCode() {
		return V1ApiException.badRequest("INVALID_CODE", "인증 코드를 확인해 주세요.");
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
