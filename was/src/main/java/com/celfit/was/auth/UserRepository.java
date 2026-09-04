package com.celfit.was.auth;

import com.celfit.was.archive.ArchiveReason;
import com.celfit.was.archive.ArchiveTable;
import com.celfit.was.archive.ArchiveTables;
import com.celfit.was.archive.ArchiveWriter;
import com.celfit.was.crypto.FieldCipher;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * app.users CRUD — email은 항상 lower 정규화해 저장·조회한다(대소문자 무관 로그인).
 *
 * <p><b>읽기는 암호문·블라인드 인덱스만 본다</b>(스펙 §전환 2, 09-04). 등가 조회는
 * {@code email_bidx = blindIndex(normalizeEmail(q))}, 표시값은 {@code decrypt(*_enc)}다 —
 * 평문 컬럼(email·name·nickname·phone_number)은 이제 <b>쓰기 전용</b>이고(PR 3에서 제거),
 * 어떤 SELECT·WHERE도 그것을 읽지 않는다. 레코드(AppUser·UserProfile) 형상은 그대로라
 * 호출부는 전환을 모른다. 복호화 실패는 {@link FieldCipher}가 예외로 던지고 여기서 삼키지
 * 않는다 — 조용한 null은 "이름이 사라진 유저"가 되어 더 오래 숨는다(스펙 §실패 모드).
 */
@Repository
public class UserRepository {

	/**
	 * UserProfile 매핑용 컬럼 목록(단일 정본) — RETURNING·SELECT가 공유한다.
	 * PII 4종은 암호문 컬럼으로 읽고 {@code profileMapper}가 복호화해 레코드를 채운다.
	 */
	private static final String PROFILE_COLUMNS = """
			id, email_enc, name_enc, nickname_enc, user_type, signup_route, phone_country_code,
			phone_number_enc, company_name, company_size, industry, job_title, agreed_marketing,
			marketing_updated_at, profile_image_url, created_at, role,
			feature_overrides::text AS feature_overrides""";

	/** AppUser 매핑용 컬럼 목록 — email은 암호문으로 읽는다. */
	private static final String USER_COLUMNS = "id, email_enc, password_hash, role, created_at";

	/**
	 * PATCH /v1/me가 갱신할 수 있는 컬럼 화이트리스트(스펙 6.13) — 동적 SET 절은 이 목록만 순회하므로
	 * 외부 입력이 SQL 조각이 될 수 없다. agreed_marketing은 marketing_updated_at 동반 갱신 특례.
	 */
	private static final List<String> PATCHABLE_COLUMNS = List.of("name", "nickname", "job_title",
			"phone_country_code", "phone_number", "company_name", "agreed_marketing");

	/**
	 * 이중 쓰기 대상(스펙 §전환 1) — PATCHABLE_COLUMNS 중 암호화 대상 컬럼만 매핑. 화이트리스트
	 * 검사 자체는 건드리지 않고, 이 목록에 있는 컬럼이 SET될 때만 코드가 파생한 `<col>_enc` SET을
	 * 추가로 붙인다(enc 컬럼명은 외부 입력이 아니라 이 맵의 상수라 화이트리스트 불변식이 유지된다).
	 */
	private static final Map<String, String> PATCH_ENC_COLUMNS = Map.of(
			"name", "name_enc", "nickname", "nickname_enc", "phone_number", "phone_number_enc");

	private final JdbcClient jdbcClient;
	private final ArchiveWriter archiveWriter;
	private final FieldCipher fieldCipher;
	private final RowMapper<AppUser> userMapper;
	private final RowMapper<UserProfile> profileMapper;

	public UserRepository(JdbcClient jdbcClient, ArchiveWriter archiveWriter, FieldCipher fieldCipher) {
		this.jdbcClient = jdbcClient;
		this.archiveWriter = archiveWriter;
		this.fieldCipher = fieldCipher;
		// 자동 매핑(query(Class))은 컬럼명 ↔ 필드명 규약에 기대는데 이제 원본이 *_enc라 규약이 깨진다 —
		// 명시 RowMapper로 전환해 복호화를 매핑 지점 하나에 모은다(레코드 형상은 불변).
		this.userMapper = (rs, rowNum) -> new AppUser(
				rs.getLong("id"),
				fieldCipher.decrypt(rs.getString("email_enc")),
				rs.getString("password_hash"),
				rs.getString("role"),
				rs.getObject("created_at", OffsetDateTime.class));
		this.profileMapper = (rs, rowNum) -> new UserProfile(
				rs.getLong("id"),
				fieldCipher.decrypt(rs.getString("email_enc")),
				fieldCipher.decrypt(rs.getString("name_enc")),
				fieldCipher.decrypt(rs.getString("nickname_enc")),
				rs.getString("user_type"),
				rs.getString("signup_route"),
				rs.getString("phone_country_code"),
				fieldCipher.decrypt(rs.getString("phone_number_enc")),
				rs.getString("company_name"),
				rs.getString("company_size"),
				rs.getString("industry"),
				rs.getString("job_title"),
				rs.getBoolean("agreed_marketing"),
				rs.getObject("marketing_updated_at", OffsetDateTime.class),
				rs.getString("profile_image_url"),
				rs.getObject("created_at", OffsetDateTime.class),
				rs.getString("role"),
				rs.getString("feature_overrides"));
	}

	/**
	 * 중복 이메일이면 DataIntegrityViolationException(구현체: DuplicateKeyException) — app.users.email
	 * UNIQUE와 email_bidx UNIQUE 둘 다 같은 예외를 낸다(읽기 전환 후에도 예외 계약 동일).
	 * 쓰기는 여전히 이중(평문+암호문) — 평문 제거는 PR 3.
	 */
	public AppUser insert(String email, String passwordHash) {
		String normalized = normalizeEmail(email);
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, email_enc, email_bidx)
				VALUES (:email, :passwordHash, :emailEnc, :emailBidx)
				RETURNING\s""" + USER_COLUMNS)
				.param("email", normalized)
				.param("passwordHash", passwordHash)
				.param("emailEnc", fieldCipher.encrypt(normalized))
				.param("emailBidx", fieldCipher.blindIndex(normalized))
				.query(userMapper)
				.single();
	}

	/**
	 * v1 가입(스펙 6.15) — 프로필 전 필드 저장. email은 lower 정규화, 중복이면 DuplicateKeyException.
	 * marketing_updated_at은 마케팅 동의(true)일 때만 가입 시각으로 기록한다(동의 시각 추적 — 스펙 6.13).
	 */
	public UserProfile insertProfile(NewUser newUser, String passwordHash) {
		String normalizedEmail = normalizeEmail(newUser.email());
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, name, nickname, user_type, signup_route,
				                       phone_country_code, phone_number, company_name, company_size,
				                       industry, job_title, usage_purpose, agreed_terms, agreed_privacy,
				                       agreed_age14, agreed_marketing, marketing_updated_at,
				                       email_enc, email_bidx, name_enc, nickname_enc, phone_number_enc)
				VALUES (:email, :passwordHash, :name, :nickname, :userType, :signupRoute,
				        :phoneCountryCode, :phoneNumber, :companyName, :companySize,
				        :industry, :jobTitle, :usagePurpose, :agreedTerms, :agreedPrivacy,
				        :agreedAge14, :agreedMarketing, CASE WHEN :agreedMarketing THEN now() END,
				        :emailEnc, :emailBidx, :nameEnc, :nicknameEnc, :phoneNumberEnc)
				RETURNING\s""" + PROFILE_COLUMNS)
				.param("email", normalizedEmail)
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
				.param("emailEnc", fieldCipher.encrypt(normalizedEmail))
				.param("emailBidx", fieldCipher.blindIndex(normalizedEmail))
				.param("nameEnc", fieldCipher.encrypt(newUser.name()))
				.param("nicknameEnc", fieldCipher.encrypt(newUser.nickname()))
				.param("phoneNumberEnc", fieldCipher.encrypt(newUser.phoneNumber()))
				.query(profileMapper)
				.single();
	}

	/** 로그인 응답(UserSummary) 조립용 프로필 — 세션의 AppUserDetails는 프로필을 안 가진다. */
	public Optional<UserProfile> findProfileByEmail(String email) {
		return jdbcClient.sql("SELECT " + PROFILE_COLUMNS + " FROM app.users WHERE email_bidx = :emailBidx")
				.param("emailBidx", fieldCipher.blindIndex(normalizeEmail(email)))
				.query(profileMapper)
				.optional();
	}

	/** GET /v1/me(스펙 6.12) — 세션의 userId로 매 요청 프로필을 읽는다. */
	public Optional<UserProfile> findProfileById(long id) {
		return jdbcClient.sql("SELECT " + PROFILE_COLUMNS + " FROM app.users WHERE id = :id")
				.param("id", id)
				.query(profileMapper)
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
		Map<String, Object> encParams = new HashMap<>();
		for (String column : PATCHABLE_COLUMNS) {
			if (!columns.containsKey(column)) {
				continue;
			}
			sets.add(column + " = :" + column);
			if (column.equals("agreed_marketing")) {
				sets.add("marketing_updated_at = CASE WHEN agreed_marketing IS DISTINCT FROM :agreed_marketing"
						+ " THEN now() ELSE marketing_updated_at END");
			}
			String encColumn = PATCH_ENC_COLUMNS.get(column);
			if (encColumn != null) {
				// enc 컬럼명은 PATCH_ENC_COLUMNS(코드 상수)에서만 나온다 — columns의 키(외부 입력)가
				// SQL 조각으로 쓰이는 것은 이 for문의 PATCHABLE_COLUMNS 순회 자체가 이미 막고 있다.
				sets.add(encColumn + " = :" + encColumn);
				encParams.put(encColumn, fieldCipher.encrypt((String) columns.get(column)));
			}
		}
		JdbcClient.StatementSpec spec = jdbcClient
				.sql("UPDATE app.users SET " + String.join(", ", sets)
						+ " WHERE id = :id RETURNING " + PROFILE_COLUMNS)
				.param("id", id);
		for (Map.Entry<String, Object> entry : columns.entrySet()) {
			spec = spec.param(entry.getKey(), entry.getValue());
		}
		for (Map.Entry<String, Object> entry : encParams.entrySet()) {
			spec = spec.param(entry.getKey(), entry.getValue());
		}
		return spec.query(profileMapper).single();
	}

	/** PUT /v1/me/password(스펙 6.13) — 현재 비밀번호 검증은 호출부(컨트롤러) 책임. */
	public void updatePasswordHash(long id, String passwordHash) {
		jdbcClient.sql("UPDATE app.users SET password_hash = :hash WHERE id = :id")
				.param("hash", passwordHash)
				.param("id", id)
				.update();
	}

	/**
	 * 최종 활동 시각 갱신(어드민 백엔드 API 설계 2026-08-01 §3, A3) — 인증된 요청마다 필터가
	 * 5분 스로틀로 호출한다. 실패는 호출부(필터)가 관측 부가 기능으로 간주해 무시한다.
	 */
	public void updateLastActiveAt(long id) {
		jdbcClient.sql("UPDATE app.users SET last_active_at = now() WHERE id = :id")
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
	 * 탈퇴(스펙 6.13) — saved 2종·brand_direct_posts는 users FK가 CASCADE가 아니거나(saved 2종)
	 * CASCADE 순서를 코드로 통제해야 해서(brand_direct_posts, 아래 참고) 자식부터 순서 삭제,
	 * 한 트랜잭션. 세션 무효화·이미지 파일 정리는 DB 밖 자원이라 호출부가 커밋 후에 수행한다.
	 *
	 * <p>삭제 전 원본 행을 전부 아카이브한다(트랙 NN). CASCADE(V16)로 사라지는 자식과
	 * registrations를 거치는 간접 CASCADE(entries)까지 ArchiveTables.ACCOUNT_DELETION_ORDER가
	 * 전부 담고 있다 — 새 자식 테이블이 생기면 ArchiveCascadeReachabilityTest가 CI에서 막는다.
	 *
	 * <p>brand_direct_posts는 users CASCADE 대상이지만 monitoring_item_id FK가 CASCADE가 아니다
	 * (V20260811090500 — ArchiveTables.BRAND_DIRECT_POSTS 참고). users delete가 monitoring_items도
	 * 함께 CASCADE로 지우므로, brand_direct_posts를 먼저 명시 삭제해두지 않으면 그 시점에 남아있는
	 * 매핑 행이 FK 위반을 낸다 — saved 2종과 같은 이유로 순서 삭제 목록에 있다.
	 *
	 * <p>brand_post_registrations·brand_post_registration_entries·brand_post_campaigns(2026-08-18
	 * direct 통합 §T13)·brand_hashtag_tags(2026-08-19 해시태그 태그 사용자 스코프 개정)는 전부
	 * users CASCADE의 순정 자식이라(직접 또는 registrations를 거친 간접) monitoring_campaigns류와
	 * 같은 위상이다 — ACCOUNT_DELETION_ORDER 루프가 이관만 하고, 실제 삭제는 아래 USERS 행 삭제의
	 * CASCADE가 담당한다(명시 DELETE 불필요).
	 */
	@Transactional
	public void deleteAccount(long id) {
		Map<ArchiveTable, Integer> archived = new HashMap<>();
		for (ArchiveTable table : ArchiveTables.ACCOUNT_DELETION_ORDER) {
			archived.put(table, archiveWriter.archiveUserScope(table, ArchiveReason.ACCOUNT_DELETION, id));
		}
		deleteAndVerify(ArchiveTables.SAVED_CONTENTS, archived,
				"DELETE FROM app.saved_contents WHERE user_id = :id", id);
		deleteAndVerify(ArchiveTables.SAVED_INFLUENCERS, archived,
				"DELETE FROM app.saved_influencers WHERE user_id = :id", id);
		deleteAndVerify(ArchiveTables.BRAND_DIRECT_POSTS, archived,
				"DELETE FROM app.brand_direct_posts WHERE user_id = :id", id);
		deleteAndVerify(ArchiveTables.USERS, archived,
				"DELETE FROM app.users WHERE id = :id", id);
	}

	/**
	 * CASCADE로 사라지는 자식 8종(campaigns·items·registrations·digests·email_opt_outs·
	 * registration_entries·notice_seen·brand_monitorings)은 DB가 지우므로 삭제 건수를 관측할 수
	 * 없다 — 코드가 직접 DELETE하는 4개(saved 2종·brand_direct_posts·users)만 이관 건수와 대조한다.
	 */
	private void deleteAndVerify(ArchiveTable table, Map<ArchiveTable, Integer> archived, String sql, long id) {
		Integer archivedCount = archived.get(table);
		if (archivedCount == null) {
			// ACCOUNT_DELETION_ORDER에서 항목이 빠졌는데 deleteAndVerify 호출부에는 남아있는 경우 —
			// int 언박싱 NPE로 원인 불명 스택트레이스를 던지는 대신 무엇이 어긋났는지 바로 말한다.
			throw new IllegalStateException(
					"이관 목록(ACCOUNT_DELETION_ORDER)에 없는 테이블: " + table.qualifiedName());
		}
		int deleted = jdbcClient.sql(sql).param("id", id).update();
		archiveWriter.verifyMatched(table, archivedCount, deleted);
	}

	/** 로그인·이메일 가용성 검사의 정본 조회 — 등가 비교는 블라인드 인덱스로 한다(평문 컬럼 미참조). */
	public Optional<AppUser> findByEmail(String email) {
		return jdbcClient.sql("SELECT " + USER_COLUMNS + " FROM app.users WHERE email_bidx = :emailBidx")
				.param("emailBidx", fieldCipher.blindIndex(normalizeEmail(email)))
				.query(userMapper)
				.optional();
	}

	public Optional<AppUser> findById(long id) {
		return jdbcClient.sql("SELECT " + USER_COLUMNS + " FROM app.users WHERE id = :id")
				.param("id", id)
				.query(userMapper)
				.optional();
	}

	/**
	 * 세션 authority 신선도 재확인 전용 경량 조회(어드민 백엔드 API 설계 §1·§2, 세션 스냅샷 재확인
	 * 결정) — role 컬럼 하나만 읽는다. 세션엔 로그인 시점 authorities가 영속돼 DB에서 강등해도
	 * 로그아웃 전까지 그대로 남으므로, 어드민 판정 두 곳(/v1/admin/** 게이트, ActAsUserFilter)이
	 * 매 요청 이 메서드로 현재 DB role을 다시 확인한다.
	 */
	public Optional<String> findRoleById(long id) {
		return jdbcClient.sql("SELECT role FROM app.users WHERE id = :id")
				.param("id", id)
				.query(String.class)
				.optional();
	}

	/** email 정규화 규칙(단일 정본) — 저장·조회와 레이트리밋 키(V1AuthController)가 같은 규칙을 공유한다. */
	public static String normalizeEmail(String email) {
		return email.toLowerCase();
	}
}
