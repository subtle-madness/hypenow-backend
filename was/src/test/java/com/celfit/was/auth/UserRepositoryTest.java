package com.celfit.was.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

/** Flyway(AppFlywayConfig)가 app 스키마를 실제로 생성한 위에서 검증 — DDL 하드코딩 없음. */
class UserRepositoryTest extends IntegrationTest {

	@Autowired
	UserRepository repository;

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
}
