package com.celfit.was.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** app.users CRUD — email은 항상 lower 정규화해 저장·조회한다(대소문자 무관 로그인). */
@Repository
public class UserRepository {

	/** UserProfile 매핑용 컬럼 목록(단일 정본) — RETURNING·SELECT가 공유한다. */
	private static final String PROFILE_COLUMNS = """
			id, email, name, nickname, user_type, signup_route, phone_country_code, phone_number,
			company_name, company_size, industry, job_title, agreed_marketing, marketing_updated_at,
			profile_image_url, created_at""";

	/**
	 * PATCH /v1/me가 갱신할 수 있는 컬럼 화이트리스트(스펙 6.13) — 동적 SET 절은 이 목록만 순회하므로
	 * 외부 입력이 SQL 조각이 될 수 없다. agreed_marketing은 marketing_updated_at 동반 갱신 특례.
	 */
	private static final List<String> PATCHABLE_COLUMNS = List.of("name", "nickname", "job_title",
			"phone_country_code", "phone_number", "company_name", "agreed_marketing");

	private final JdbcClient jdbcClient;

	public UserRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 중복 이메일이면 DataIntegrityViolationException(구현체: DuplicateKeyException) — app.users.email UNIQUE 제약. */
	public AppUser insert(String email, String passwordHash) {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash)
				VALUES (:email, :passwordHash)
				RETURNING id, email, password_hash, role, created_at
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
	public UserProfile insertProfile(NewUser newUser, String passwordHash) {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, name, nickname, user_type, signup_route,
				                       phone_country_code, phone_number, company_name, company_size,
				                       industry, job_title, usage_purpose, agreed_terms, agreed_privacy,
				                       agreed_age14, agreed_marketing, marketing_updated_at)
				VALUES (:email, :passwordHash, :name, :nickname, :userType, :signupRoute,
				        :phoneCountryCode, :phoneNumber, :companyName, :companySize,
				        :industry, :jobTitle, :usagePurpose, :agreedTerms, :agreedPrivacy,
				        :agreedAge14, :agreedMarketing, CASE WHEN :agreedMarketing THEN now() END)
				RETURNING\s""" + PROFILE_COLUMNS)
				.param("email", normalizeEmail(newUser.email()))
				.param("passwordHash", passwordHash)
				.param("name", newUser.name())
				.param("nickname", newUser.nickname())
				.param("userType", newUser.userType())
				.param("signupRoute", newUser.signupRoute())
				.param("phoneCountryCode", newUser.phoneCountryCode())
				.param("phoneNumber", newUser.phoneNumber())
				.param("companyName", newUser.companyName())
				.param("companySize", newUser.companySize())
				.param("industry", newUser.industry())
				.param("jobTitle", newUser.jobTitle())
				.param("usagePurpose", newUser.usagePurpose())
				.param("agreedTerms", newUser.agreedTerms())
				.param("agreedPrivacy", newUser.agreedPrivacy())
				.param("agreedAge14", newUser.agreedAge14())
				.param("agreedMarketing", newUser.agreedMarketing())
				.query(UserProfile.class)
				.single();
	}

	/** 로그인 응답(UserSummary) 조립용 프로필 — 세션의 AppUserDetails는 프로필을 안 가진다. */
	public Optional<UserProfile> findProfileByEmail(String email) {
		return jdbcClient.sql("SELECT " + PROFILE_COLUMNS + " FROM app.users WHERE email = :email")
				.param("email", normalizeEmail(email))
				.query(UserProfile.class)
				.optional();
	}

	/** GET /v1/me(스펙 6.12) — 세션의 userId로 매 요청 프로필을 읽는다. */
	public Optional<UserProfile> findProfileById(long id) {
		return jdbcClient.sql("SELECT " + PROFILE_COLUMNS + " FROM app.users WHERE id = :id")
				.param("id", id)
				.query(UserProfile.class)
				.optional();
	}

	/**
	 * PATCH /v1/me 부분 갱신(스펙 6.13) — columns의 키는 PATCHABLE_COLUMNS 화이트리스트(컬럼명)만 허용,
	 * 존재하는 키만 SET 한다(SavedRepository upsertInfluencer의 "미지정이면 유지" 관용구의 동적 SQL 판).
	 * agreed_marketing은 **값이 실제로 바뀔 때만** marketing_updated_at=now() — UPDATE의 SET 우변은
	 * 항상 갱신 전 행을 보므로 IS DISTINCT FROM 비교가 옛 값 기준으로 성립한다.
	 */
	public UserProfile patchProfile(long id, Map<String, Object> columns) {
		if (columns.isEmpty() || !PATCHABLE_COLUMNS.containsAll(columns.keySet())) {
			throw new IllegalArgumentException("patch 화이트리스트 밖 컬럼: " + columns.keySet());
		}
		List<String> sets = new ArrayList<>();
		for (String column : PATCHABLE_COLUMNS) {
			if (!columns.containsKey(column)) {
				continue;
			}
			sets.add(column + " = :" + column);
			if (column.equals("agreed_marketing")) {
				sets.add("marketing_updated_at = CASE WHEN agreed_marketing IS DISTINCT FROM :agreed_marketing"
						+ " THEN now() ELSE marketing_updated_at END");
			}
		}
		JdbcClient.StatementSpec spec = jdbcClient
				.sql("UPDATE app.users SET " + String.join(", ", sets)
						+ " WHERE id = :id RETURNING " + PROFILE_COLUMNS)
				.param("id", id);
		for (Map.Entry<String, Object> entry : columns.entrySet()) {
			spec = spec.param(entry.getKey(), entry.getValue());
		}
		return spec.query(UserProfile.class).single();
	}

	/** PUT /v1/me/password(스펙 6.13) — 현재 비밀번호 검증은 호출부(컨트롤러) 책임. */
	public void updatePasswordHash(long id, String passwordHash) {
		jdbcClient.sql("UPDATE app.users SET password_hash = :hash WHERE id = :id")
				.param("hash", passwordHash)
				.param("id", id)
				.update();
	}

	/** 프로필 이미지 URL 갱신(스펙 6.13) — null이면 이미지 제거. */
	public void updateProfileImageUrl(long id, String profileImageUrl) {
		jdbcClient.sql("UPDATE app.users SET profile_image_url = :url WHERE id = :id")
				.param("url", profileImageUrl)
				.param("id", id)
				.update();
	}

	/**
	 * 탈퇴(스펙 6.13) — saved 2종은 users FK가 CASCADE가 아니라 자식부터 순서 삭제, 한 트랜잭션.
	 * 세션 무효화·이미지 파일 정리는 DB 밖 자원이라 호출부가 커밋 후에 수행한다.
	 */
	@Transactional
	public void deleteAccount(long id) {
		jdbcClient.sql("DELETE FROM app.saved_contents WHERE user_id = :id").param("id", id).update();
		jdbcClient.sql("DELETE FROM app.saved_influencers WHERE user_id = :id").param("id", id).update();
		jdbcClient.sql("DELETE FROM app.users WHERE id = :id").param("id", id).update();
	}

	public Optional<AppUser> findByEmail(String email) {
		return jdbcClient.sql("""
				SELECT id, email, password_hash, role, created_at
				FROM app.users
				WHERE email = :email
				""")
				.param("email", normalizeEmail(email))
				.query(AppUser.class)
				.optional();
	}

	public Optional<AppUser> findById(long id) {
		return jdbcClient.sql("""
				SELECT id, email, password_hash, role, created_at
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
