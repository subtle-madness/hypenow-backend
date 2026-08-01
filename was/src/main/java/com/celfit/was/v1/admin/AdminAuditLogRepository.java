package com.celfit.was.v1.admin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.admin_audit_logs CRUD(어드민 백엔드 API 설계 2026-08-01 §2·§3) — X-Act-As-User 사칭 기록.
 * insert는 {@link com.celfit.was.security.ActAsUserFilter}가, 보존 삭제는
 * {@link AdminAuditLogRetentionScheduler}가 쓴다. 조회는 §4 GET /v1/admin/audit-logs(AdminAuditLogController).
 */
@Repository
public class AdminAuditLogRepository {

	private final JdbcClient jdbcClient;

	public AdminAuditLogRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 쿼리스트링은 기록하지 않는다(개인정보 유입 차단 — path만, §2). */
	public void insert(long adminId, long targetUserId, String path) {
		jdbcClient.sql("""
				INSERT INTO app.admin_audit_logs (admin_id, target_user_id, path)
				VALUES (:adminId, :targetUserId, :path)
				""")
				.param("adminId", adminId)
				.param("targetUserId", targetUserId)
				.param("path", path)
				.update();
	}

	/** 보존 90일(A8) — cutoff 이전 행 전량 삭제, 반환값은 삭제 건수(로그용). */
	public int deleteOlderThan(OffsetDateTime cutoff) {
		return jdbcClient.sql("DELETE FROM app.admin_audit_logs WHERE at < :cutoff")
				.param("cutoff", cutoff)
				.update();
	}

	/** GET /v1/admin/audit-logs(§4) — adminId/targetUserId AND 필터 + 페이지네이션, at 내림차순. */
	public Page findPage(Long adminId, Long targetUserId, int limit, int offset) {
		List<String> conditions = new ArrayList<>();
		Map<String, Object> params = new LinkedHashMap<>();
		if (adminId != null) {
			conditions.add("admin_id = :adminId");
			params.put("adminId", adminId);
		}
		if (targetUserId != null) {
			conditions.add("target_user_id = :targetUserId");
			params.put("targetUserId", targetUserId);
		}
		String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);

		JdbcClient.StatementSpec listSpec = jdbcClient.sql("""
				SELECT id, admin_id, target_user_id, path, at FROM app.admin_audit_logs %s
				ORDER BY at DESC
				LIMIT :limit OFFSET :offset
				""".formatted(where))
				.param("limit", limit)
				.param("offset", offset);
		JdbcClient.StatementSpec countSpec =
				jdbcClient.sql("SELECT count(*) FROM app.admin_audit_logs %s".formatted(where));
		for (Map.Entry<String, Object> entry : params.entrySet()) {
			listSpec = listSpec.param(entry.getKey(), entry.getValue());
			countSpec = countSpec.param(entry.getKey(), entry.getValue());
		}

		List<Row> rows = listSpec.query(Row.class).list();
		long total = countSpec.query(Long.class).single();
		return new Page(rows, total);
	}

	public record Row(long id, long adminId, long targetUserId, String path, OffsetDateTime at) {
	}

	public record Page(List<Row> rows, long total) {
	}
}
