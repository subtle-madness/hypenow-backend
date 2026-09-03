package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.auth.NewUser;
import com.celfit.was.auth.UserProfile;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.crypto.FieldCipher;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * users 이중 쓰기(스펙 §전환 1) — 가입·패치가 평문과 *_enc/*_bidx를 항상 함께 채우는지.
 * 베이스 클래스·컨테이너 설정은 기존 통합 테스트(UserRepositoryTest 등) 관례를 그대로 따른다.
 */
class UserRepositoryDualWriteTest extends IntegrationTest {

	@Autowired UserRepository userRepository;
	@Autowired JdbcClient jdbcClient;
	@Autowired FieldCipher fieldCipher;

	private NewUser newUser(String email) {
		return new NewUser(email, "김철수", "철수", "brand", "portal_search",
				"+82", "01012345678", "하이프나우", "2-10", "beauty", "staff", null,
				true, true, true, false);
	}

	@Test
	void 가입은_평문과_암호문을_함께_쓴다() {
		UserProfile profile = userRepository.insertProfile(newUser("Dual@Ex.com"), "hash");

		Map<String, Object> row = jdbcClient.sql(
				"SELECT email, email_enc, email_bidx, name_enc, nickname_enc, phone_number_enc FROM app.users WHERE id = :id")
				.param("id", profile.id()).query().singleRow();

		assertThat(row.get("email")).isEqualTo("dual@ex.com"); // 평문 유지(이중 쓰기 기간 정본)
		assertThat(fieldCipher.decrypt((String) row.get("email_enc"))).isEqualTo("dual@ex.com");
		assertThat(row.get("email_bidx")).isEqualTo(fieldCipher.blindIndex("dual@ex.com"));
		assertThat(fieldCipher.decrypt((String) row.get("name_enc"))).isEqualTo("김철수");
		assertThat(fieldCipher.decrypt((String) row.get("nickname_enc"))).isEqualTo("철수");
		assertThat(fieldCipher.decrypt((String) row.get("phone_number_enc"))).isEqualTo("01012345678");
	}

	@Test
	void 프로필_패치도_암호문을_함께_갱신한다() {
		UserProfile profile = userRepository.insertProfile(newUser("patch-dual@example.com"), "hash");

		userRepository.patchProfile(profile.id(), Map.of("name", "박영희", "phone_number", "01099998888"));

		Map<String, Object> row = jdbcClient.sql(
				"SELECT name, name_enc, phone_number_enc FROM app.users WHERE id = :id")
				.param("id", profile.id()).query().singleRow();
		assertThat(row.get("name")).isEqualTo("박영희");
		assertThat(fieldCipher.decrypt((String) row.get("name_enc"))).isEqualTo("박영희");
		assertThat(fieldCipher.decrypt((String) row.get("phone_number_enc"))).isEqualTo("01099998888");
	}

	@Test
	void 간이_insert도_이메일_암호문과_블라인드_인덱스를_채운다() {
		var user = userRepository.insert("Simple@Ex.com", "hash");

		Map<String, Object> row = jdbcClient.sql(
				"SELECT email_enc, email_bidx FROM app.users WHERE id = :id")
				.param("id", user.id()).query().singleRow();
		assertThat(fieldCipher.decrypt((String) row.get("email_enc"))).isEqualTo("simple@ex.com");
		assertThat(row.get("email_bidx")).isEqualTo(fieldCipher.blindIndex("simple@ex.com"));
	}

	@Test
	void 패치가_name_nickname_phone_외의_컬럼만_바꾸면_enc_컬럼은_그대로다() {
		UserProfile profile = userRepository.insertProfile(newUser("untouched@example.com"), "hash");

		Map<String, Object> before = jdbcClient.sql(
				"SELECT name_enc FROM app.users WHERE id = :id")
				.param("id", profile.id()).query().singleRow();

		userRepository.patchProfile(profile.id(), Map.of("job_title", "team_lead"));

		Map<String, Object> after = jdbcClient.sql(
				"SELECT name_enc, job_title FROM app.users WHERE id = :id")
				.param("id", profile.id()).query().singleRow();
		assertThat(after.get("name_enc")).isEqualTo(before.get("name_enc"));
		assertThat(after.get("job_title")).isEqualTo("team_lead");
	}
}
