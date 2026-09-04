package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.auth.UserRepository;
import com.celfit.was.crypto.FieldCipher;
import com.celfit.was.crypto.PiiVerifyRunner;
import com.celfit.was.crypto.PiiVerifyRunner.VerifyReport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PII 정합 검증 러너(트랙 A PR 2 Task 8-0 게이트) — 평문 vs decrypt(enc), bidx vs
 * HMAC(normalize(평문)) 불일치를 잡아내는지 확인한다. 러너는 {@code @ConditionalOnProperty
 * (crypto.verify=true)}라 기본 테스트 컨텍스트엔 빈으로 뜨지 않으므로 PiiBackfillRunnerTest와
 * 동일하게 캐싱된 컨텍스트의 JdbcClient·FieldCipher로 {@code new PiiVerifyRunner(...)}를 직접
 * 생성한다.
 *
 * <p>공유 컨테이너에 다른 테스트(PiiBackfillRunnerTest 등)가 남긴 정상 행이 있을 수 있으므로
 * 절대 카운트가 아니라 "시드 전 기준선 대비 증가분"으로 단언한다.
 */
class PiiVerifyRunnerTest extends IntegrationTest {

	@Autowired JdbcClient jdbcClient;
	@Autowired FieldCipher fieldCipher;

	private PiiVerifyRunner runner() {
		return new PiiVerifyRunner(jdbcClient, fieldCipher);
	}

	@Test
	void users_enc_불일치와_bidx_불일치_행을_각각_잡아낸다() {
		PiiVerifyRunner runner = runner();
		VerifyReport before = runner.verifyAll();

		// 정합 행 — email/name 모두 올바른 enc, bidx도 정상
		String emailOk = "verify-ok-" + UUID.randomUUID() + "@ex.com";
		String nameOk = "정상";
		Long idOk = insertUser(emailOk, nameOk, fieldCipher.encrypt(emailOk),
				fieldCipher.blindIndex(UserRepository.normalizeEmail(emailOk)), fieldCipher.encrypt(nameOk));

		// enc 불일치 행 — name_enc가 다른 평문의 암호문(email_bidx는 정상)
		String emailEncBad = "verify-encbad-" + UUID.randomUUID() + "@ex.com";
		String nameEncBad = "원본이름";
		Long idEncBad = insertUser(emailEncBad, nameEncBad, fieldCipher.encrypt(emailEncBad),
				fieldCipher.blindIndex(UserRepository.normalizeEmail(emailEncBad)), fieldCipher.encrypt("다른이름"));

		// bidx 불일치 행 — email_bidx가 엉뚱한 값(enc는 전부 정상)
		String emailBidxBad = "verify-bidxbad-" + UUID.randomUUID() + "@ex.com";
		String nameBidxBad = "정상2";
		Long idBidxBad = insertUser(emailBidxBad, nameBidxBad, fieldCipher.encrypt(emailBidxBad),
				"bogus-bidx-" + UUID.randomUUID(), fieldCipher.encrypt(nameBidxBad));

		try {
			VerifyReport after = runner.verifyAll();

			assertThat(after.encMismatch().get("users") - before.encMismatch().getOrDefault("users", 0))
					.isEqualTo(1);
			assertThat(after.bidxMismatch().get("users") - before.bidxMismatch().getOrDefault("users", 0))
					.isEqualTo(1);
			// 다른 테이블은 이 테스트가 건드리지 않았으니 변화 없어야 한다
			for (String table : List.of("inquiries", "password_resets", "signup_events")) {
				assertThat(after.encMismatch().get(table)).isEqualTo(before.encMismatch().get(table));
				assertThat(after.bidxMismatch().get(table)).isEqualTo(before.bidxMismatch().get(table));
			}
		} finally {
			jdbcClient.sql("DELETE FROM app.users WHERE id IN (:ids)")
					.param("ids", List.of(idOk, idEncBad, idBidxBad))
					.update();
		}
	}

	private Long insertUser(String email, String name, String emailEnc, String emailBidx, String nameEnc) {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, name, email_enc, email_bidx, name_enc)
				VALUES (:email, 'h', :name, :emailEnc, :emailBidx, :nameEnc)
				RETURNING id""")
				.param("email", email)
				.param("name", name)
				.param("emailEnc", emailEnc)
				.param("emailBidx", emailBidx)
				.param("nameEnc", nameEnc)
				.query(Long.class).single();
	}
}
