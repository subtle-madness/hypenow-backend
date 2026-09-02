package com.celfit.was.v1.admin;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

/**
 * app.users 조회(어드민 백엔드 API 설계 2026-08-01 §4 GET /v1/admin/users·/users/{id}) —
 * email·name 부분일치(ILIKE) 검색 + 페이지네이션. 쓰기는 기능 플래그 교체
 * ({@link #updateFeatureOverrides}, 2026-08-31) 하나뿐이고 나머지는 전부 읽기 전용이다.
 */
@Repository
public class AdminUserRepository {

	private static final String COLUMNS = """
			id, email, name, user_type, signup_route, company_name, job_title, created_at, last_active_at,
			feature_overrides::text AS feature_overrides""";

	private final JdbcClient jdbcClient;

	public AdminUserRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * 목록(§4 GET /v1/admin/users) — query는 email·name 부분일치(ILIKE) OR 조건, 공백만이면 무시.
	 * sort는 지원하지 않아 상수 정렬(created_at DESC)만 존재한다(호출부가 파라미터 자체를 버린다).
	 */
	public Page findPage(String query, int limit, int offset) {
		String normalized = normalize(query);
		String where = normalized == null ? "" : "WHERE email ILIKE :q OR name ILIKE :q";

		StatementSpec listSpec = jdbcClient.sql("""
				SELECT %s FROM app.users %s
				ORDER BY created_at DESC
				LIMIT :limit OFFSET :offset
				""".formatted(COLUMNS, where))
				.param("limit", limit)
				.param("offset", offset);
		StatementSpec countSpec = jdbcClient.sql("SELECT count(*) FROM app.users %s".formatted(where));
		if (normalized != null) {
			String like = "%" + normalized + "%";
			listSpec = listSpec.param("q", like);
			countSpec = countSpec.param("q", like);
		}

		List<AdminUserRow> rows = listSpec.query(AdminUserRow.class).list();
		long total = countSpec.query(Long.class).single();
		return new Page(rows, total);
	}

	public Optional<AdminUserRow> findById(long id) {
		return jdbcClient.sql("SELECT " + COLUMNS + " FROM app.users WHERE id = :id")
				.param("id", id)
				.query(AdminUserRow.class)
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
				.query(AdminUserRow.class)
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
