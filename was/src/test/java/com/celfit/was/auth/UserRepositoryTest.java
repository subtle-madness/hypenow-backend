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
				"+82", "010-1234-5678", "하이프나우", "2-10", "beauty", "staff",
				true, true, true, agreedMarketing);
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
}
