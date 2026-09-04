package com.celfit.was.v1.admin;

import com.celfit.was.crypto.FieldCipher;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.users 조회(어드민 백엔드 API 설계 2026-08-01 §4 GET /v1/admin/users·/users/{id}) —
 * email·name 부분일치 검색 + 페이지네이션. 쓰기는 기능 플래그 교체
 * ({@link #updateFeatureOverrides}, 2026-08-31) 하나뿐이고 나머지는 전부 읽기 전용이다.
 *
 * <p><b>검색은 SQL ILIKE가 아니라 메모리 필터다</b>(스펙 §전환 2, 09-04 읽기 전환). email·name이
 * 암호문(AES-GCM, 랜덤 IV)이라 DB가 부분일치를 볼 수 없다 — 전체를 읽어 복호화한 뒤 자바에서
 * {@code contains}(대소문자 무시)로 거르고 페이지를 손으로 자른다. <b>클로즈베타 규모(유저 수백
 * 명) 전제의 의도된 한계</b>다: 검색 1회가 users 전 행을 읽는다. 유저가 수만 명대로 커지면
 * 이 방식을 유지할 수 없고, 정규화 토큰의 블라인드 인덱스(부분일치 불가)나 별도 검색 인덱스
 * 같은 다른 설계가 필요하다. 검색어가 없는 경로는 그대로 SQL 페이지네이션을 쓴다.
 */
@Repository
public class AdminUserRepository {

	private static final String COLUMNS = """
			id, email_enc, name_enc, user_type, signup_route, company_name, job_title, created_at,
			last_active_at, feature_overrides::text AS feature_overrides""";

	private final JdbcClient jdbcClient;
	private final RowMapper<AdminUserRow> rowMapper;

	public AdminUserRepository(JdbcClient jdbcClient, FieldCipher fieldCipher) {
		this.jdbcClient = jdbcClient;
		this.rowMapper = (rs, rowNum) -> new AdminUserRow(
				rs.getLong("id"),
				fieldCipher.decrypt(rs.getString("email_enc")),
				fieldCipher.decrypt(rs.getString("name_enc")),
				rs.getString("user_type"),
				rs.getString("signup_route"),
				rs.getString("company_name"),
				rs.getString("job_title"),
				rs.getObject("created_at", OffsetDateTime.class),
				rs.getObject("last_active_at", OffsetDateTime.class),
				rs.getString("feature_overrides"));
	}

	/**
	 * 목록(§4 GET /v1/admin/users) — query는 email·name 부분일치 OR 조건, 공백만이면 무시.
	 * sort는 지원하지 않아 상수 정렬(created_at DESC)만 존재한다(호출부가 파라미터 자체를 버린다).
	 * 검색어가 있으면 클래스 주석의 메모리 필터 경로를 탄다(total도 필터 후 건수).
	 */
	public Page findPage(String query, int limit, int offset) {
		String normalized = normalize(query);
		if (normalized != null) {
			return searchInMemory(normalized, limit, offset);
		}
		List<AdminUserRow> rows = jdbcClient.sql("""
				SELECT %s FROM app.users
				ORDER BY created_at DESC
				LIMIT :limit OFFSET :offset
				""".formatted(COLUMNS))
				.param("limit", limit)
				.param("offset", offset)
				.query(rowMapper)
				.list();
		long total = jdbcClient.sql("SELECT count(*) FROM app.users").query(Long.class).single();
		return new Page(rows, total);
	}

	/** 전 행 복호화 → contains 필터 → 수동 페이지네이션. 정렬은 SQL이 이미 created_at DESC로 준다. */
	private Page searchInMemory(String query, int limit, int offset) {
		String needle = query.toLowerCase(Locale.ROOT);
		List<AdminUserRow> matched = jdbcClient
				.sql("SELECT %s FROM app.users ORDER BY created_at DESC".formatted(COLUMNS))
				.query(rowMapper)
				.list()
				.stream()
				.filter(row -> contains(row.email(), needle) || contains(row.name(), needle))
				.toList();
		if (offset >= matched.size()) {
			return new Page(List.of(), matched.size());
		}
		return new Page(matched.subList(offset, Math.min(offset + limit, matched.size())), matched.size());
	}

	private static boolean contains(String value, String lowerNeedle) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(lowerNeedle);
	}

	public Optional<AdminUserRow> findById(long id) {
		return jdbcClient.sql("SELECT " + COLUMNS + " FROM app.users WHERE id = :id")
				.param("id", id)
				.query(rowMapper)
				.optional();
	}

	/**
	 * PUT /v1/admin/users/{id}/features(2026-08-31) — 병합이 아니라 전체 교체다. 부재 유저면
	 * RETURNING이 0행이라 빈 Optional이 나온다(호출부가 404로 옮긴다). 반환값은 <b>DB에 실제로
	 * 저장된 값</b> — jsonb는 키 순서 정규화·중복 키 제거를 하므로 입력 문자열을 그대로 되돌려주면
	 * 저장값과 어긋날 수 있다.
	 */
	public Optional<String> updateFeatureOverrides(long id, String overridesJson) {
		return jdbcClient.sql("""
				UPDATE app.users SET feature_overrides = CAST(:json AS jsonb)
				WHERE id = :id
				RETURNING feature_overrides::text
				""")
				.param("json", overridesJson)
				.param("id", id)
				.query(String.class)
				.optional();
	}

	/** GET /v1/admin/monitoring/registrations(§4)의 userName Java 결합용 배치 조회. */
	public List<AdminUserRow> findByIds(Collection<Long> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbcClient.sql("SELECT " + COLUMNS + " FROM app.users WHERE id IN (:ids)")
				.param("ids", ids)
				.query(rowMapper)
				.list();
	}

	private static String normalize(String query) {
		if (query == null) {
			return null;
		}
		String trimmed = query.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	public record Page(List<AdminUserRow> rows, long total) {
	}
}
