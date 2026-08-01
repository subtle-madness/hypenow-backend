package com.celfit.was.v1.admin;

import com.celfit.was.v1.common.KstTimestamps;

/** GET /v1/admin/audit-logs 행(설계 2026-08-01 §4) — id들은 문자열, at은 KST ISO. */
public record AdminAuditLogEntry(String id, String adminId, String targetUserId, String path, String at) {

	public static AdminAuditLogEntry from(AdminAuditLogRepository.Row row) {
		return new AdminAuditLogEntry(String.valueOf(row.id()), String.valueOf(row.adminId()),
				String.valueOf(row.targetUserId()), row.path(), KstTimestamps.toKstIso(row.at()));
	}
}
