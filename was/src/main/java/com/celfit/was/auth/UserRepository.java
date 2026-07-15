package com.celfit.was.auth;

import com.celfit.was.v1.account.SignupRequest;
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
				.param("email", normalizeEmail(email))
				.param("passwordHash", passwordHash)
				.query(AppUser.class)
				.single();
	}

	/**
	 * v1 가입(스펙 6.15) — 프로필 전 필드 저장. email은 lower 정규화, 중복이면 DuplicateKeyException.
	 * marketing_updated_at은 마케팅 동의(true)일 때만 가입 시각으로 기록한다(동의 시각 추적 — 스펙 6.13).
	 */
	public UserProfile insertProfile(SignupRequest request, String passwordHash) {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, name, nickname, user_type, signup_route,
				                       phone_country_code, phone_number, company_name, company_size,
				                       industry, job_title, agreed_terms, agreed_privacy, agreed_age14,
				                       agreed_marketing, marketing_updated_at)
				VALUES (:email, :passwordHash, :name, :nickname, :userType, :signupRoute,
				        :phoneCountryCode, :phoneNumber, :companyName, :companySize,
				        :industry, :jobTitle, :agreedTerms, :agreedPrivacy, :agreedAge14,
				        :agreedMarketing, CASE WHEN :agreedMarketing THEN now() END)
				RETURNING id, email, name, user_type
				""")
				.param("email", normalizeEmail(request.email()))
				.param("passwordHash", passwordHash)
				.param("name", request.name())
				.param("nickname", request.nickname())
				.param("userType", request.userType())
				.param("signupRoute", request.signupRoute())
				.param("phoneCountryCode", request.phoneCountryCode())
				.param("phoneNumber", request.phoneNumber())
				.param("companyName", request.companyName())
				.param("companySize", request.companySize())
				.param("industry", request.industry())
				.param("jobTitle", request.jobTitle())
				.param("agreedTerms", Boolean.TRUE.equals(request.agreedTerms()))
				.param("agreedPrivacy", Boolean.TRUE.equals(request.agreedPrivacy()))
				.param("agreedAge14", Boolean.TRUE.equals(request.agreedAge14()))
				.param("agreedMarketing", request.marketingAgreed())
				.query(UserProfile.class)
				.single();
	}

	/** 로그인 응답(UserSummary) 조립용 프로필 요약 — 세션의 AppUserDetails는 프로필을 안 가진다. */
	public Optional<UserProfile> findProfileByEmail(String email) {
		return jdbcClient.sql("""
				SELECT id, email, name, user_type
				FROM app.users
				WHERE email = :email
				""")
				.param("email", normalizeEmail(email))
				.query(UserProfile.class)
				.optional();
	}

	public Optional<AppUser> findByEmail(String email) {
		return jdbcClient.sql("""
				SELECT id, email, password_hash, created_at
				FROM app.users
				WHERE email = :email
				""")
				.param("email", normalizeEmail(email))
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

	/** email 정규화 규칙(단일 정본) — 저장·조회와 레이트리밋 키(V1AuthController)가 같은 규칙을 공유한다. */
	public static String normalizeEmail(String email) {
		return email.toLowerCase();
	}
}
