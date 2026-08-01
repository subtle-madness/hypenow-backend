package com.celfit.was.v1.admin;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.admin_audit_logs CRUD(어드민 백엔드 API 설계 2026-08-01 §2·§3) — X-Act-As-User 사칭 기록.
 * insert는 {@link com.celfit.was.security.ActAsUserFilter}가, 보존 삭제는
 * {@link AdminAuditLogRetentionScheduler}가 쓴다. 조회(§4 GET /v1/admin/audit-logs)는 후속 구현 몫.
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
}
