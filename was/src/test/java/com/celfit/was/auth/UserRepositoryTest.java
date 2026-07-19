package com.celfit.was.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Flyway(AppFlywayConfig)가 app 스키마를 실제로 생성한 위에서 검증 — DDL 하드코딩 없음. */
class UserRepositoryTest extends IntegrationTest {

	@Autowired
	UserRepository repository;

	@Autowired
	JdbcClient jdbcClient;

	private NewUser newUser(String email, boolean agreedMarketing) {
		return new NewUser(email, "김우민", "우민", "brand", "portal_search",
				"+82", "010-1234-5678", "하이프나우", "2-10", "beauty", "staff", null,
				true, true, true, agreedMarketing);
	}

	// 가입 경량화(2026-07-19) — 선택 필드 null 저장 + usage_purpose(대행사 활용 목적) 저장
	@Test
	void insertProfile은_선택_필드_null과_usage_purpose를_저장한다() {
		NewUser lean = new NewUser("lean@example.com", "김우민", null, "agency", null,
				null, null, "OO대행사", null, null, null, "브랜드 캠페인 인플루언서 발굴",
				true, true, true, false);

		UserProfile saved = repository.insertProfile(lean, "hashed-lean");

		Map<String, Object> row = jdbcClient.sql("""
				SELECT signup_route, phone_country_code, phone_number, company_size, industry,
				       job_title, usage_purpose FROM app.users WHERE id = :id""")
				.param("id", saved.id())
				.query().singleRow();
		assertThat(row.get("signup_route")).isNull();
		assertThat(row.get("phone_country_code")).isNull();
		assertThat(row.get("phone_number")).isNull();
		assertThat(row.get("company_size")).isNull();
		assertThat(row.get("industry")).isNull();
		assertThat(row.get("job_title")).isNull();
		assertThat(row.get("usage_purpose")).isEqualTo("브랜드 캠페인 인플루언서 발굴");
	}

	@Test
	void insert는_email을_lower로_정규화해_저장한다() {
		AppUser saved = repository.insert("User@Example.com", "hashed-1");

		assertThat(saved.id()).isPositive();
		assertThat(saved.email()).isEqualTo("user@example.com");
		assertThat(saved.passwordHash()).isEqualTo("hashed-1");
		assertThat(saved.createdAt()).isNotNull();
	}

	@Test
	void 중복_이메일_insert는_DuplicateKeyException이다() {
		repository.insert("dup@example.com", "hashed-2");

		assertThatThrownBy(() -> repository.insert("DUP@example.com", "hashed-3"))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void findByEmail은_대소문자_무관하게_조회한다() {
		repository.insert("find@example.com", "hashed-4");

		Optional<AppUser> found = repository.findByEmail("FIND@example.com");

		assertThat(found).isPresent();
		assertThat(found.get().email()).isEqualTo("find@example.com");
	}

	@Test
	void findById은_없는_id면_empty다() {
		Optional<AppUser> found = repository.findById(-1L);

		assertThat(found).isEmpty();
	}

	// --- v1 프로필 확장(V3, 스펙 6.15) ---

	@Test
	void insertProfile은_프로필_전_필드를_저장하고_email을_lower_정규화한다() {
		UserProfile saved = repository.insertProfile(newUser("Profile@Example.com", true), "hashed-p1");

		assertThat(saved.id()).isPositive();
		assertThat(saved.email()).isEqualTo("profile@example.com");
		assertThat(saved.name()).isEqualTo("김우민");
		assertThat(saved.userType()).isEqualTo("brand");

		Map<String, Object> row = jdbcClient.sql("SELECT * FROM app.users WHERE id = :id")
				.param("id", saved.id())
				.query()
				.singleRow();
		assertThat(row.get("nickname")).isEqualTo("우민");
		assertThat(row.get("signup_route")).isEqualTo("portal_search");
		assertThat(row.get("phone_country_code")).isEqualTo("+82");
		assertThat(row.get("phone_number")).isEqualTo("010-1234-5678");
		assertThat(row.get("company_name")).isEqualTo("하이프나우");
		assertThat(row.get("company_size")).isEqualTo("2-10");
		assertThat(row.get("industry")).isEqualTo("beauty");
		assertThat(row.get("job_title")).isEqualTo("staff");
		assertThat(row.get("agreed_terms")).isEqualTo(true);
		assertThat(row.get("agreed_privacy")).isEqualTo(true);
		assertThat(row.get("agreed_age14")).isEqualTo(true);
		assertThat(row.get("agreed_marketing")).isEqualTo(true);
		assertThat(row.get("marketing_updated_at")).isNotNull(); // 마케팅 동의 → 동의 시각 기록
		assertThat(row.get("password_hash")).isEqualTo("hashed-p1");
	}

	@Test
	void insertProfile_마케팅_미동의면_marketing_updated_at은_null이다() {
		UserProfile saved = repository.insertProfile(newUser("profile2@example.com", false), "hashed-p2");

		Map<String, Object> row = jdbcClient.sql("SELECT agreed_marketing, marketing_updated_at FROM app.users WHERE id = :id")
				.param("id", saved.id())
				.query()
				.singleRow();
		assertThat(row.get("agreed_marketing")).isEqualTo(false);
		assertThat(row.get("marketing_updated_at")).isNull();
	}

	@Test
	void insertProfile_중복_이메일은_DuplicateKeyException이다() {
		repository.insertProfile(newUser("dup-profile@example.com", false), "hashed-p3");

		assertThatThrownBy(() ->
				repository.insertProfile(newUser("DUP-PROFILE@example.com", false), "hashed-p4"))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void findProfileByEmail은_대소문자_무관하게_프로필_요약을_돌려준다() {
		repository.insertProfile(newUser("find-profile@example.com", false), "hashed-p5");

		Optional<UserProfile> found = repository.findProfileByEmail("FIND-PROFILE@example.com");

		assertThat(found).isPresent();
		assertThat(found.get().name()).isEqualTo("김우민");
		assertThat(found.get().userType()).isEqualTo("brand");
	}

	// --- /v1/me 확장(T3, 스펙 6.12~6.13) ---

	@Test
	void findProfileById는_프로필_전_필드를_돌려준다() {
		UserProfile saved = repository.insertProfile(newUser("me-full@example.com", true), "hashed-m1");

		UserProfile found = repository.findProfileById(saved.id()).orElseThrow();

		assertThat(found.email()).isEqualTo("me-full@example.com");
		assertThat(found.nickname()).isEqualTo("우민");
		assertThat(found.signupRoute()).isEqualTo("portal_search");
		assertThat(found.phoneCountryCode()).isEqualTo("+82");
		assertThat(found.phoneNumber()).isEqualTo("010-1234-5678");
		assertThat(found.companyName()).isEqualTo("하이프나우");
		assertThat(found.companySize()).isEqualTo("2-10");
		assertThat(found.industry()).isEqualTo("beauty");
		assertThat(found.jobTitle()).isEqualTo("staff");
		assertThat(found.agreedMarketing()).isTrue();
		assertThat(found.marketingUpdatedAt()).isNotNull();
		assertThat(found.profileImageUrl()).isNull();
		assertThat(found.createdAt()).isNotNull();
	}

	@Test
	void patchProfile은_주어진_컬럼만_바꾸고_나머지는_유지한다() {
		UserProfile saved = repository.insertProfile(newUser("patch@example.com", false), "hashed-m2");

		Map<String, Object> columns = new java.util.LinkedHashMap<>();
		columns.put("name", "새이름");
		columns.put("nickname", null); // 닉네임 제거(빈 문자열 입력의 저장 형태)
		UserProfile patched = repository.patchProfile(saved.id(), columns);

		assertThat(patched.name()).isEqualTo("새이름");
		assertThat(patched.nickname()).isNull();
		assertThat(patched.phoneNumber()).isEqualTo("010-1234-5678"); // 미지정 컬럼 유지
		assertThat(patched.companyName()).isEqualTo("하이프나우");
	}

	@Test
	void patchProfile_agreedMarketing은_값이_바뀔_때만_동의_시각을_갱신한다() {
		UserProfile saved = repository.insertProfile(newUser("marketing@example.com", false), "hashed-m3");
		assertThat(saved.marketingUpdatedAt()).isNull();

		// false→true: 변경 — 시각 기록
		UserProfile turnedOn = repository.patchProfile(saved.id(), Map.of("agreed_marketing", true));
		assertThat(turnedOn.agreedMarketing()).isTrue();
		assertThat(turnedOn.marketingUpdatedAt()).isNotNull();

		// true→true: 같은 값 재전송 — 시각 유지
		UserProfile resent = repository.patchProfile(saved.id(), Map.of("agreed_marketing", true));
		assertThat(resent.marketingUpdatedAt()).isEqualTo(turnedOn.marketingUpdatedAt());

		// true→false: 변경 — 철회 시각으로 다시 갱신
		UserProfile turnedOff = repository.patchProfile(saved.id(), Map.of("agreed_marketing", false));
		assertThat(turnedOff.agreedMarketing()).isFalse();
		assertThat(turnedOff.marketingUpdatedAt()).isAfterOrEqualTo(turnedOn.marketingUpdatedAt());
	}

	@Test
	void patchProfile_화이트리스트_밖_컬럼은_IllegalArgumentException이다() {
		UserProfile saved = repository.insertProfile(newUser("whitelist@example.com", false), "hashed-m4");

		assertThatThrownBy(() -> repository.patchProfile(saved.id(), Map.of("email", "hack@example.com")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> repository.patchProfile(saved.id(), Map.of()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void updatePasswordHash는_해시만_바꾼다() {
		UserProfile saved = repository.insertProfile(newUser("pwchange@example.com", false), "hashed-old");

		repository.updatePasswordHash(saved.id(), "hashed-new");

		assertThat(repository.findById(saved.id()).orElseThrow().passwordHash()).isEqualTo("hashed-new");
	}

	@Test
	void updateProfileImageUrl은_설정과_null_제거를_오간다() {
		UserProfile saved = repository.insertProfile(newUser("image@example.com", false), "hashed-m5");

		repository.updateProfileImageUrl(saved.id(), "/profile-images/user-" + saved.id() + ".png?v=1");
		assertThat(repository.findProfileById(saved.id()).orElseThrow().profileImageUrl())
				.isEqualTo("/profile-images/user-" + saved.id() + ".png?v=1");

		repository.updateProfileImageUrl(saved.id(), null);
		assertThat(repository.findProfileById(saved.id()).orElseThrow().profileImageUrl()).isNull();
	}

	@Test
	void deleteAccount는_saved_2종과_users를_함께_지운다() {
		UserProfile saved = repository.insertProfile(newUser("withdraw@example.com", false), "hashed-m6");
		jdbcClient.sql("INSERT INTO app.saved_influencers (user_id, handle) VALUES (:id, 'someone')")
				.param("id", saved.id()).update();
		jdbcClient.sql("INSERT INTO app.saved_contents (user_id, short_code) VALUES (:id, 'ABC123')")
				.param("id", saved.id()).update();

		repository.deleteAccount(saved.id());

		assertThat(count("app.saved_influencers", saved.id())).isZero();
		assertThat(count("app.saved_contents", saved.id())).isZero();
		assertThat(repository.findById(saved.id())).isEmpty();
	}

	private long count(String table, long userId) {
		return jdbcClient.sql("SELECT count(*) FROM " + table + " WHERE user_id = :id")
				.param("id", userId)
				.query(Long.class)
				.single();
	}
}
