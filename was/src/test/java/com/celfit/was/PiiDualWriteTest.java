package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.auth.UserRepository;
import com.celfit.was.crypto.FieldCipher;
import com.celfit.was.v1.account.PasswordResetRepository;
import com.celfit.was.v1.account.SignupEventRecorder;
import com.celfit.was.v1.inquiry.InquiryRepository;
import com.celfit.was.v1.inquiry.InquiryRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * inquiries·password_resets·signup_events 이중 쓰기(스펙 §전환 1, Task 5) — 세 테이블 각각
 * 평문과 *_enc(+email은 *_bidx)를 함께 기록하는지. 베이스·검증 방식은 UserRepositoryDualWriteTest 관례.
 */
class PiiDualWriteTest extends IntegrationTest {

	@Autowired InquiryRepository inquiryRepository;
	@Autowired PasswordResetRepository passwordResetRepository;
	@Autowired SignupEventRecorder signupEventRecorder;
	@Autowired JdbcClient jdbcClient;
	@Autowired FieldCipher fieldCipher;

	@Test
	void 도입문의_저장은_4개_컬럼_암호문을_함께_쓴다() {
		InquiryRequest request = new InquiryRequest("brand", "김철수", "Inquiry@Ex.com", "하이프나우", "문의 내용입니다");

		UUID id = inquiryRepository.insert(request);

		Map<String, Object> row = jdbcClient.sql(
				"SELECT name_enc, email_enc, organization_enc, message_enc FROM app.inquiries WHERE id = :id")
				.param("id", id).query().singleRow();
		assertThat(fieldCipher.decrypt((String) row.get("name_enc"))).isEqualTo("김철수");
		assertThat(fieldCipher.decrypt((String) row.get("email_enc"))).isEqualTo("Inquiry@Ex.com");
		assertThat(fieldCipher.decrypt((String) row.get("organization_enc"))).isEqualTo("하이프나우");
		assertThat(fieldCipher.decrypt((String) row.get("message_enc"))).isEqualTo("문의 내용입니다");
	}

	@Test
	void 비번_재설정_upsert는_이메일_암호문과_블라인드_인덱스를_채운다() {
		jdbcClient.sql("DELETE FROM app.password_resets").update();
		String email = "reset-dual@example.com";

		passwordResetRepository.upsert(email, "code-hash-1", Instant.now().plusSeconds(300));

		Map<String, Object> row = jdbcClient.sql(
				"SELECT email_enc, email_bidx FROM app.password_resets WHERE email = :email")
				.param("email", email).query().singleRow();
		assertThat(fieldCipher.decrypt((String) row.get("email_enc"))).isEqualTo(email);
		assertThat(row.get("email_bidx")).isEqualTo(fieldCipher.blindIndex(UserRepository.normalizeEmail(email)));
	}

	@Test
	void 비번_재설정_재발송_upsert는_암호문도_갱신한다() {
		jdbcClient.sql("DELETE FROM app.password_resets").update();
		String email = "reset-dual-2@example.com";
		passwordResetRepository.upsert(email, "code-hash-1", Instant.now().plusSeconds(300));

		passwordResetRepository.upsert(email, "code-hash-2", Instant.now().plusSeconds(300));

		Map<String, Object> row = jdbcClient.sql(
				"SELECT email_enc, email_bidx FROM app.password_resets WHERE email = :email")
				.param("email", email).query().singleRow();
		assertThat(fieldCipher.decrypt((String) row.get("email_enc"))).isEqualTo(email);
		assertThat(row.get("email_bidx")).isEqualTo(fieldCipher.blindIndex(UserRepository.normalizeEmail(email)));
	}

	@Test
	void 가입_이벤트_기록은_원문_이메일과_정규화_블라인드_인덱스와_ip_암호문을_함께_쓴다() {
		signupEventRecorder.record("A@b.com", SignupEventRecorder.OUTCOME_OK, "203.0.113.9", Map.of());

		Map<String, Object> row = jdbcClient.sql(
				"SELECT email_enc, email_bidx, ip_enc FROM app.signup_events WHERE email = 'A@b.com' ORDER BY id DESC LIMIT 1")
				.query().singleRow();
		// 원문 보존 — 정규화 없이 그대로 암호화(기존 email 평문 컬럼과 동일 의미론)
		assertThat(fieldCipher.decrypt((String) row.get("email_enc"))).isEqualTo("A@b.com");
		// bidx만 정규화된 조회 키
		assertThat(row.get("email_bidx")).isEqualTo(fieldCipher.blindIndex(UserRepository.normalizeEmail("A@b.com")));
		assertThat(fieldCipher.decrypt((String) row.get("ip_enc"))).isEqualTo("203.0.113.9");
	}

	@Test
	void 가입_이벤트_이메일이_null이면_평문처럼_빈문자열_암호문을_쓴다() {
		signupEventRecorder.record(null, "fail", "203.0.113.10", Map.of());

		Map<String, Object> row = jdbcClient.sql(
				"SELECT email, email_enc, email_bidx FROM app.signup_events WHERE ip = '203.0.113.10' ORDER BY id DESC LIMIT 1")
				.query().singleRow();
		assertThat(row.get("email")).isEqualTo("");
		assertThat(fieldCipher.decrypt((String) row.get("email_enc"))).isEqualTo("");
		assertThat(row.get("email_bidx")).isEqualTo(fieldCipher.blindIndex(UserRepository.normalizeEmail("")));
	}
}
