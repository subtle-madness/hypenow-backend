package com.celfit.was.crypto;

import com.celfit.was.auth.UserRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개인정보 암호화 백필(스펙 §전환 2) — *_enc IS NULL 행만 채우는 멱등 커맨드.
 * 기동 플래그 --crypto.backfill=true일 때만 실행(운영 롤아웃 §Task 7 러너 절차 참조).
 * 클로즈베타 규모(users 104명)라 전량 단순 루프 — 수천 행 초과 시 배치 분할로 재작업.
 */
@Component
@ConditionalOnProperty(name = "crypto.backfill", havingValue = "true")
public class PiiBackfillRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(PiiBackfillRunner.class);

	private final JdbcClient jdbc;
	private final FieldCipher cipher;

	public PiiBackfillRunner(JdbcClient jdbc, FieldCipher cipher) {
		this.jdbc = jdbc;
		this.cipher = cipher;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		backfillAll();
	}

	public void backfillAll() {
		int users = backfillUsers();
		int inquiries = backfillInquiries();
		int resets = backfillPasswordResets();
		int events = backfillSignupEvents();
		log.info("PII 백필 완료 — users={}, inquiries={}, password_resets={}, signup_events={}",
				users, inquiries, resets, events);
	}

	private int backfillUsers() {
		List<Map<String, Object>> rows = jdbc.sql("""
				SELECT id, email, name, nickname, phone_number FROM app.users WHERE email_enc IS NULL""")
				.query().listOfRows();
		for (Map<String, Object> r : rows) {
			String email = (String) r.get("email");
			jdbc.sql("""
					UPDATE app.users SET email_enc = :ee, email_bidx = :eb, name_enc = :ne,
					       nickname_enc = :ke, phone_number_enc = :pe WHERE id = :id""")
					.param("ee", cipher.encrypt(email))
					.param("eb", cipher.blindIndex(UserRepository.normalizeEmail(email)))
					.param("ne", cipher.encrypt((String) r.get("name")))
					.param("ke", cipher.encrypt((String) r.get("nickname")))
					.param("pe", cipher.encrypt((String) r.get("phone_number")))
					.param("id", r.get("id"))
					.update();
		}
		return rows.size();
	}

	private int backfillInquiries() {
		List<Map<String, Object>> rows = jdbc.sql("""
				SELECT id, name, email, organization, message FROM app.inquiries WHERE email_enc IS NULL""")
				.query().listOfRows();
		for (Map<String, Object> r : rows) {
			jdbc.sql("""
					UPDATE app.inquiries SET name_enc = :ne, email_enc = :ee,
					       organization_enc = :oe, message_enc = :me WHERE id = :id""")
					.param("ne", cipher.encrypt((String) r.get("name")))
					.param("ee", cipher.encrypt((String) r.get("email")))
					.param("oe", cipher.encrypt((String) r.get("organization")))
					.param("me", cipher.encrypt((String) r.get("message")))
					.param("id", r.get("id"))
					.update();
		}
		return rows.size();
	}

	private int backfillPasswordResets() {
		List<Map<String, Object>> rows = jdbc.sql("""
				SELECT email FROM app.password_resets WHERE email_enc IS NULL""")
				.query().listOfRows();
		for (Map<String, Object> r : rows) {
			String email = (String) r.get("email");
			jdbc.sql("""
					UPDATE app.password_resets SET email_enc = :ee, email_bidx = :eb WHERE email = :email""")
					.param("ee", cipher.encrypt(email))
					.param("eb", cipher.blindIndex(UserRepository.normalizeEmail(email)))
					.param("email", email)
					.update();
		}
		return rows.size();
	}

	private int backfillSignupEvents() {
		List<Map<String, Object>> rows = jdbc.sql("""
				SELECT id, email, ip FROM app.signup_events WHERE email_enc IS NULL""")
				.query().listOfRows();
		for (Map<String, Object> r : rows) {
			String email = (String) r.get("email");
			String ip = (String) r.get("ip");
			jdbc.sql("""
					UPDATE app.signup_events SET email_enc = :ee, email_bidx = :eb, ip_enc = :ie WHERE id = :id""")
					.param("ee", cipher.encrypt(email))
					.param("eb", cipher.blindIndex(UserRepository.normalizeEmail(email)))
					.param("ie", cipher.encrypt(ip))
					.param("id", r.get("id"))
					.update();
		}
		return rows.size();
	}
}
