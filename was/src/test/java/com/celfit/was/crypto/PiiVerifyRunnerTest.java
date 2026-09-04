package com.celfit.was.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.celfit.was.IntegrationTest;
import com.celfit.was.auth.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
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
 *
 * <p>{@code com.celfit.was.crypto} 패키지에 둔 이유: {@link PiiVerifyRunner#PASSWORD_RESETS_SQL}이
 * 패키지-프라이빗이라(리뷰 라운드 1 — password_resets는 PK가 email이라 WARN 로그에 평문 이메일이
 * 노출되는 결함 수정) 같은 패키지에서만 "email AS id로 노출하지 않는다"를 코드로 고정할 수 있다.
 * {@code DekStoreTest}와 동일한 선례.
 */
class PiiVerifyRunnerTest extends IntegrationTest {

	@Autowired JdbcClient jdbcClient;
	@Autowired FieldCipher fieldCipher;

	private PiiVerifyRunner runner() {
		// failFast=false 고정 — exitAbnormally는 이 경로를 타지 않으므로 context=null이 안전하다.
		return new PiiVerifyRunner(jdbcClient, fieldCipher, false, null);
	}

	@Test
	void password_resets_sql은_email을_id로_노출하지_않는다() {
		assertThat(PiiVerifyRunner.PASSWORD_RESETS_SQL).doesNotContain("email AS id");
	}

	@Test
	void shouldExitAbnormally는_failFast_true이고_합계가_0보다_클_때만_true다() {
		assertThat(PiiVerifyRunner.shouldExitAbnormally(true, 1)).isTrue();
		assertThat(PiiVerifyRunner.shouldExitAbnormally(true, 0)).isFalse();
		assertThat(PiiVerifyRunner.shouldExitAbnormally(false, 1)).isFalse();
		assertThat(PiiVerifyRunner.shouldExitAbnormally(false, 0)).isFalse();
	}

	@Test
	void run은_failFast가_false면_불일치가_있어도_컨텍스트를_건드리지_않고_정상_반환한다() {
		// context=null인 runner()로 run()을 직접 호출 — shouldExitAbnormally가 false를 반환해야만
		// exitAbnormally(null 컨텍스트 역참조)를 안 타므로, 예외 없이 끝나는 것 자체가 분기 증거다.
		PiiVerifyRunner runner = runner();

		assertThatCode(() -> runner.run(new DefaultApplicationArguments()))
				.doesNotThrowAnyException();
	}

	@Test
	void users_enc_불일치와_bidx_불일치_행을_각각_잡아낸다() {
		PiiVerifyRunner runner = runner();
		VerifyReportSnapshot before = snapshot(runner);

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
			VerifyReportSnapshot after = snapshot(runner);

			assertThat(after.encUsers - before.encUsers).isEqualTo(1);
			assertThat(after.bidxUsers - before.bidxUsers).isEqualTo(1);
			// 다른 테이블은 이 테스트가 건드리지 않았으니 변화 없어야 한다
			assertThat(after.encInquiries).isEqualTo(before.encInquiries);
			assertThat(after.bidxInquiries).isEqualTo(before.bidxInquiries);
			assertThat(after.encPasswordResets).isEqualTo(before.encPasswordResets);
			assertThat(after.bidxPasswordResets).isEqualTo(before.bidxPasswordResets);
			assertThat(after.encSignupEvents).isEqualTo(before.encSignupEvents);
			assertThat(after.bidxSignupEvents).isEqualTo(before.bidxSignupEvents);
		} finally {
			jdbcClient.sql("DELETE FROM app.users WHERE id IN (:ids)")
					.param("ids", List.of(idOk, idEncBad, idBidxBad))
					.update();
		}
	}

	@Test
	void password_resets_bidx_불일치_행을_잡아낸다() {
		PiiVerifyRunner runner = runner();
		VerifyReportSnapshot before = snapshot(runner);

		String email = "verify-reset-bidxbad-" + UUID.randomUUID() + "@ex.com";
		jdbcClient.sql("""
				INSERT INTO app.password_resets (email, code_hash, code_expires_at, email_enc, email_bidx)
				VALUES (:email, 'code-hash', now() + interval '5 minutes', :emailEnc, :emailBidx)""")
				.param("email", email)
				.param("emailEnc", fieldCipher.encrypt(email))
				.param("emailBidx", "bogus-bidx-" + UUID.randomUUID())
				.update();

		try {
			VerifyReportSnapshot after = snapshot(runner);

			assertThat(after.bidxPasswordResets - before.bidxPasswordResets).isEqualTo(1);
			assertThat(after.encPasswordResets).isEqualTo(before.encPasswordResets);
		} finally {
			jdbcClient.sql("DELETE FROM app.password_resets WHERE email = :email")
					.param("email", email)
					.update();
		}
	}

	private VerifyReportSnapshot snapshot(PiiVerifyRunner runner) {
		PiiVerifyRunner.VerifyReport r = runner.verifyAll();
		return new VerifyReportSnapshot(
				r.encMismatch().getOrDefault("users", 0), r.bidxMismatch().getOrDefault("users", 0),
				r.encMismatch().getOrDefault("inquiries", 0), r.bidxMismatch().getOrDefault("inquiries", 0),
				r.encMismatch().getOrDefault("password_resets", 0), r.bidxMismatch().getOrDefault("password_resets", 0),
				r.encMismatch().getOrDefault("signup_events", 0), r.bidxMismatch().getOrDefault("signup_events", 0));
	}

	private record VerifyReportSnapshot(int encUsers, int bidxUsers, int encInquiries, int bidxInquiries,
			int encPasswordResets, int bidxPasswordResets, int encSignupEvents, int bidxSignupEvents) {
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
