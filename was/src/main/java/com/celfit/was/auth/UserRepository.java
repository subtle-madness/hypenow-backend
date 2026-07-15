package com.celfit.was.auth;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** app.users CRUD — email은 항상 lower 정규화해 저장·조회한다(대소문자 무관 로그인). */
@Repository
public class UserRepository {

	private final JdbcClient jdbcClient;

	public UserRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 중복 이메일이면 DataIntegrityViolationException(구현체: DuplicateKeyException) — app.users.email UNIQUE 제약. */
	public AppUser insert(String email, String passwordHash) {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash)
				VALUES (:email, :passwordHash)
				RETURNING id, email, password_hash, created_at
				""")
				.param("email", normalize(email))
				.param("passwordHash", passwordHash)
				.query(AppUser.class)
				.single();
	}

	public Optional<AppUser> findByEmail(String email) {
		return jdbcClient.sql("""
				SELECT id, email, password_hash, created_at
				FROM app.users
				WHERE email = :email
				""")
				.param("email", normalize(email))
				.query(AppUser.class)
				.optional();
	}

	public Optional<AppUser> findById(long id) {
		return jdbcClient.sql("""
				SELECT id, email, password_hash, created_at
				FROM app.users
				WHERE id = :id
				""")
				.param("id", id)
				.query(AppUser.class)
				.optional();
	}

	private String normalize(String email) {
		return email.toLowerCase();
	}
}
