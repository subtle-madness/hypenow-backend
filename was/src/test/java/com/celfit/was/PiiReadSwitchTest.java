package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetailsService;
import com.celfit.was.auth.NewUser;
import com.celfit.was.auth.UserProfile;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.v1.account.PasswordResetRepository;
import com.celfit.was.v1.admin.AdminUserRepository;
import com.celfit.was.v1.admin.AdminUserRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * PII 읽기 전환(스펙 §전환 2, Task 8) — 조회가 평문 컬럼을 더는 읽지 않는다는 <b>직접 증명</b>.
 * 방법: 정상 저장(이중 쓰기)으로 행을 만든 뒤 <b>평문 컬럼만 구식 값으로 오염</b>시키고,
 * 조회가 (a) bidx로 그 행을 찾아내고 (b) 복호화한 값을 돌려주는지 본다 — 평문을 읽고 있었다면
 * 행을 못 찾거나 오염된 값이 새어 나온다.
 */
class PiiReadSwitchTest extends IntegrationTest {

	@Autowired UserRepository userRepository;
	@Autowired AdminUserRepository adminUserRepository;
	@Autowired PasswordResetRepository passwordResetRepository;
	@Autowired AppUserDetailsService appUserDetailsService;
	@Autowired JdbcClient jdbcClient;

	private static NewUser newUser(String email, String name) {
		return new NewUser(email, name, "닉", "brand", "portal_search", "+82", "010-1234-5678",
				"하이프나우", "2-10", "beauty", "staff", null, true, true, true, false);
	}

	/** 평문 email·name을 구식 값으로 덮는다 — 읽기가 평문을 본다면 여기서 티가 난다. */
	private void 평문을_오염시킨다(long id) {
		jdbcClient.sql("UPDATE app.users SET email = :stale, name = '오염된이름' WHERE id = :id")
				.param("stale", "stale-" + id + "@x.com")
				.param("id", id)
				.update();
	}

	@Test
	void 평문이_오염돼도_findByEmail은_bidx로_찾고_복호화_값을_돌려준다() {
		String email = "read-switch-1@example.com";
		UserProfile created = userRepository.insertProfile(newUser(email, "박읽기전환"), "hash");
		평문을_오염시킨다(created.id());

		Optional<AppUser> found = userRepository.findByEmail(email);

		assertThat(found).isPresent();
		assertThat(found.get().id()).isEqualTo(created.id());
		assertThat(found.get().email()).isEqualTo(email);
	}

	@Test
	void 평문이_오염돼도_프로필_조회는_복호화_값을_돌려준다() {
		String email = "read-switch-2@example.com";
		UserProfile created = userRepository.insertProfile(newUser(email, "박읽기전환"), "hash");
		평문을_오염시킨다(created.id());

		UserProfile byEmail = userRepository.findProfileByEmail(email).orElseThrow();
		UserProfile byId = userRepository.findProfileById(created.id()).orElseThrow();

		assertThat(byEmail.id()).isEqualTo(created.id());
		assertThat(byEmail.email()).isEqualTo(email);
		assertThat(byEmail.name()).isEqualTo("박읽기전환");
		assertThat(byId.email()).isEqualTo(email);
		assertThat(byId.name()).isEqualTo("박읽기전환");
		assertThat(byId.phoneNumber()).isEqualTo("010-1234-5678");
	}

	@Test
	void 평문이_오염돼도_로그인_조회는_왕복한다() {
		String email = "read-switch-3@example.com";
		UserProfile created = userRepository.insertProfile(newUser(email, "박읽기전환"), "hash");
		평문을_오염시킨다(created.id());

		UserDetails details = appUserDetailsService.loadUserByUsername(email.toUpperCase());

		// principal은 userId 문자열을 username으로 쓴다(트랙 A 09-03) — 조회 성공 자체가 bidx 왕복의 증거
		assertThat(details.getUsername()).isEqualTo(String.valueOf(created.id()));
	}

	@Test
	void 어드민_검색은_암호화된_이름을_메모리에서_부분일치시킨다() {
		String email = "read-switch-4@example.com";
		UserProfile created = userRepository.insertProfile(newUser(email, "김검색대상철수"), "hash");
		평문을_오염시킨다(created.id());

		AdminUserRepository.Page page = adminUserRepository.findPage("검색대상철수", 20, 0);

		assertThat(page.total()).isEqualTo(1);
		assertThat(page.rows()).singleElement()
				.satisfies(row -> {
					assertThat(row.id()).isEqualTo(created.id());
					assertThat(row.name()).isEqualTo("김검색대상철수");
					assertThat(row.email()).isEqualTo(email);
				});
	}

	@Test
	void 어드민_검색은_이메일_부분일치도_대소문자를_무시한다() {
		String email = "read-switch-5-UniQue@example.com";
		UserProfile created = userRepository.insertProfile(newUser(email, "박읽기전환"), "hash");
		평문을_오염시킨다(created.id());

		AdminUserRepository.Page page = adminUserRepository.findPage("READ-SWITCH-5-unique", 20, 0);

		assertThat(page.total()).isEqualTo(1);
		assertThat(page.rows()).singleElement()
				// 저장은 lower 정규화 — 복호화 값도 정규화된 값이다
				.satisfies(row -> assertThat(row.id()).isEqualTo(created.id()));
	}

	@Test
	void 검색어_없는_어드민_목록도_복호화_값을_돌려준다() {
		String email = "read-switch-6@example.com";
		UserProfile created = userRepository.insertProfile(newUser(email, "박목록"), "hash");
		평문을_오염시킨다(created.id());

		AdminUserRepository.Page page = adminUserRepository.findPage(null, 5, 0);

		// created_at DESC라 방금 만든 행이 첫 페이지에 있다
		assertThat(page.rows()).anySatisfy(row -> {
			assertThat(row.id()).isEqualTo(created.id());
			assertThat(row.email()).isEqualTo(email);
			assertThat(row.name()).isEqualTo("박목록");
		});
		List<AdminUserRow> byId = adminUserRepository.findByIds(List.of(created.id()));
		assertThat(byId).singleElement().satisfies(row -> {
			assertThat(row.email()).isEqualTo(email);
			assertThat(row.name()).isEqualTo("박목록");
		});
		assertThat(adminUserRepository.findById(created.id()).orElseThrow().email()).isEqualTo(email);
	}

	@Test
	void 비번_재설정_흐름은_평문이_오염돼도_bidx로_완주한다() {
		String email = "read-switch-7@example.com";
		jdbcClient.sql("DELETE FROM app.password_resets WHERE email_bidx IS NOT NULL").update();
		passwordResetRepository.upsert(email, "code-hash", Instant.now().plusSeconds(300));
		jdbcClient.sql("UPDATE app.password_resets SET email = 'stale-reset@x.com'").update();

		PasswordResetRepository.ResetChallenge challenge = passwordResetRepository.find(email).orElseThrow();
		assertThat(challenge.email()).isEqualTo(email);
		assertThat(challenge.attempts()).isZero();

		passwordResetRepository.incrementAttempts(email);
		assertThat(passwordResetRepository.find(email).orElseThrow().attempts()).isEqualTo(1);

		boolean consumed = passwordResetRepository.consumeCodeAndIssueToken(
				email, "code-hash", "token-hash", Instant.now().plusSeconds(600));
		assertThat(consumed).isTrue();

		PasswordResetRepository.ClaimedToken claimed =
				passwordResetRepository.claimByTokenHash("token-hash").orElseThrow();
		assertThat(claimed.email()).isEqualTo(email);
	}

	@Test
	void 평문_이메일이_달라도_같은_이메일_재가입은_bidx_UNIQUE로_막힌다() {
		String email = "read-switch-8@example.com";
		UserProfile created = userRepository.insertProfile(newUser(email, "박중복"), "hash");
		// 평문 UNIQUE는 비켜 가게 만든다 — 남는 방어선은 email_bidx UNIQUE뿐
		평문을_오염시킨다(created.id());

		assertThatThrownBy(() -> userRepository.insertProfile(newUser(email, "박중복2"), "hash"))
				.isInstanceOf(DuplicateKeyException.class);
		assertThatThrownBy(() -> userRepository.insert(email, "hash"))
				.isInstanceOf(DuplicateKeyException.class);
	}
}
