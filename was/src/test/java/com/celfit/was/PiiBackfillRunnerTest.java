package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.auth.UserRepository;
import com.celfit.was.crypto.FieldCipher;
import com.celfit.was.crypto.PiiBackfillRunner;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PII 백필 커맨드(스펙 §전환 2, Task 6) — 이중 쓰기 이전에 심어진 평문뿐인 레거시 행을
 * *_enc/*_bidx로 채우는지, 그리고 재실행해도 값이 바뀌지 않는지(멱등) 4개 테이블 전부 확인한다.
 *
 * <p>러너는 {@code @ConditionalOnProperty(crypto.backfill=true)}라 기본 테스트 컨텍스트엔
 * 빈으로 뜨지 않는다. 플래그를 켜서 별도 Spring 컨텍스트를 새로 띄우는 대신, IntegrationTest가
 * 캐싱해 공유하는 컨텍스트에서 JdbcClient·FieldCipher만 받아 {@code new PiiBackfillRunner(...)}로
 * 직접 생성한다(운영 기동 경로는 ApplicationRunner#run이지만, 테스트는 backfillAll()을 직접 호출).
 * {@code @Transactional}은 {@code run()}에 붙어 있다(트랙 A 09-03 리뷰 수정 — 원래 backfillAll()에
 * 있었으나 run()이 this.backfillAll()로 자기호출해 프록시를 안 타는 바람에 운영 기동 경로에서도
 * 무의미했다). 이 테스트는 직접 new한 인스턴스의 backfillAll()을 호출하므로(run()이 아니라) 어느
 * 쪽이든 프록시가 없어 트랜잭션은 여전히 무의미하지만, 각 UPDATE가 자체 오토커밋으로 반영되므로
 * 이 테스트가 검증하는 백필 결과·멱등성과는 무관하다.
 */
class PiiBackfillRunnerTest extends IntegrationTest {

	@Autowired JdbcClient jdbcClient;
	@Autowired FieldCipher fieldCipher;

	private PiiBackfillRunner runner() {
		return new PiiBackfillRunner(jdbcClient, fieldCipher);
	}

	@Test
	void users_레거시_행을_백필하고_재실행해도_값이_바뀌지_않는다() {
		String email = "legacy-user-" + UUID.randomUUID() + "@ex.com";
		Long id = jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, name) VALUES (:email, 'h', '레거시')
				RETURNING id""")
				.param("email", email)
				.query(Long.class).single();

		PiiBackfillRunner runner = runner();
		runner.backfillAll();

		Map<String, Object> row = jdbcClient.sql("""
				SELECT email_enc, email_bidx, name_enc, nickname_enc, phone_number_enc
				FROM app.users WHERE id = :id""")
				.param("id", id).query().singleRow();
		assertThat(fieldCipher.decrypt((String) row.get("email_enc"))).isEqualTo(email);
		assertThat(row.get("email_bidx")).isEqualTo(fieldCipher.blindIndex(UserRepository.normalizeEmail(email)));
		assertThat(fieldCipher.decrypt((String) row.get("name_enc"))).isEqualTo("레거시");
		// nickname·phone_number는 시드에서 값을 주지 않은 NULL 컬럼(V9로 둘 다 NOT NULL·기본값 제거됨)
		// — 암호화 결과도 NULL 그대로(FieldCipher.encrypt(null) == null)여야 한다
		assertThat(row.get("nickname_enc")).isNull();
		assertThat(row.get("phone_number_enc")).isNull();

		runner.backfillAll();
		Map<String, Object> row2 = jdbcClient.sql("""
				SELECT email_enc, email_bidx, name_enc FROM app.users WHERE id = :id""")
				.param("id", id).query().singleRow();
		// 재실행해도 값 불변 — 재암호화됐다면 IV가 달라져 email_enc 문자열이 달라진다
		assertThat(row2.get("email_enc")).isEqualTo(row.get("email_enc"));
		assertThat(row2.get("email_bidx")).isEqualTo(row.get("email_bidx"));
		assertThat(row2.get("name_enc")).isEqualTo(row.get("name_enc"));
	}

	@Test
	void inquiries_레거시_행을_백필하고_재실행해도_값이_바뀌지_않는다() {
		String email = "legacy-inquiry-" + UUID.randomUUID() + "@ex.com";
		UUID id = jdbcClient.sql("""
				INSERT INTO app.inquiries (user_type, name, email, organization, message)
				VALUES ('brand', '김철수', :email, '하이프나우', '문의 내용입니다')
				RETURNING id""")
				.param("email", email)
				.query(UUID.class).single();

		PiiBackfillRunner runner = runner();
		runner.backfillAll();

		Map<String, Object> row = jdbcClient.sql("""
				SELECT name_enc, email_enc, organization_enc, message_enc
				FROM app.inquiries WHERE id = :id""")
				.param("id", id).query().singleRow();
		assertThat(fieldCipher.decrypt((String) row.get("name_enc"))).isEqualTo("김철수");
		assertThat(fieldCipher.decrypt((String) row.get("email_enc"))).isEqualTo(email);
		assertThat(fieldCipher.decrypt((String) row.get("organization_enc"))).isEqualTo("하이프나우");
		assertThat(fieldCipher.decrypt((String) row.get("message_enc"))).isEqualTo("문의 내용입니다");

		runner.backfillAll();
		Map<String, Object> row2 = jdbcClient.sql("""
				SELECT name_enc, email_enc, organization_enc, message_enc
				FROM app.inquiries WHERE id = :id""")
				.param("id", id).query().singleRow();
		assertThat(row2).isEqualTo(row);
	}

	@Test
	void password_resets_레거시_행을_백필하고_재실행해도_값이_바뀌지_않는다() {
		String email = "legacy-reset-" + UUID.randomUUID() + "@ex.com";
		jdbcClient.sql("""
				INSERT INTO app.password_resets (email, code_hash, code_expires_at)
				VALUES (:email, 'code-hash', now() + interval '5 minutes')""")
				.param("email", email)
				.update();

		PiiBackfillRunner runner = runner();
		runner.backfillAll();

		Map<String, Object> row = jdbcClient.sql("""
				SELECT email_enc, email_bidx FROM app.password_resets WHERE email = :email""")
				.param("email", email).query().singleRow();
		assertThat(fieldCipher.decrypt((String) row.get("email_enc"))).isEqualTo(email);
		assertThat(row.get("email_bidx")).isEqualTo(fieldCipher.blindIndex(UserRepository.normalizeEmail(email)));

		runner.backfillAll();
		Map<String, Object> row2 = jdbcClient.sql("""
				SELECT email_enc, email_bidx FROM app.password_resets WHERE email = :email""")
				.param("email", email).query().singleRow();
		assertThat(row2).isEqualTo(row);
	}

	@Test
	void signup_events_레거시_행을_백필하고_재실행해도_값이_바뀌지_않는다() {
		String email = "legacy-signup-" + UUID.randomUUID() + "@ex.com";
		Long id = jdbcClient.sql("""
				INSERT INTO app.signup_events (email, outcome, ip) VALUES (:email, 'ok', '203.0.113.77')
				RETURNING id""")
				.param("email", email)
				.query(Long.class).single();

		PiiBackfillRunner runner = runner();
		runner.backfillAll();

		Map<String, Object> row = jdbcClient.sql("""
				SELECT email_enc, email_bidx, ip_enc FROM app.signup_events WHERE id = :id""")
				.param("id", id).query().singleRow();
		assertThat(fieldCipher.decrypt((String) row.get("email_enc"))).isEqualTo(email);
		assertThat(row.get("email_bidx")).isEqualTo(fieldCipher.blindIndex(UserRepository.normalizeEmail(email)));
		assertThat(fieldCipher.decrypt((String) row.get("ip_enc"))).isEqualTo("203.0.113.77");

		runner.backfillAll();
		Map<String, Object> row2 = jdbcClient.sql("""
				SELECT email_enc, email_bidx, ip_enc FROM app.signup_events WHERE id = :id""")
				.param("id", id).query().singleRow();
		assertThat(row2).isEqualTo(row);
	}

	@Test
	void signup_events_이메일이_빈문자열이어도_이중쓰기와_동일하게_암호화한다() {
		// V14: email NOT NULL — signup_events는 가입 전 단계라 null 대신 ""로 저장되는 경우가 있다
		// (SignupEventRecorder.record와 동일 의미론). 백필도 특수 취급 없이 그대로 encrypt(email)해야 한다.
		Long id = jdbcClient.sql("""
				INSERT INTO app.signup_events (email, outcome, ip) VALUES ('', 'VALIDATION_FAILED', '203.0.113.88')
				RETURNING id""")
				.query(Long.class).single();

		runner().backfillAll();

		Map<String, Object> row = jdbcClient.sql("""
				SELECT email_enc, email_bidx FROM app.signup_events WHERE id = :id""")
				.param("id", id).query().singleRow();
		assertThat(fieldCipher.decrypt((String) row.get("email_enc"))).isEqualTo("");
		assertThat(row.get("email_bidx")).isEqualTo(fieldCipher.blindIndex(UserRepository.normalizeEmail("")));
	}
}
