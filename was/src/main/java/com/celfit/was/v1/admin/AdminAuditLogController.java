package com.celfit.was.v1.admin;

import com.celfit.was.v1.common.ApiResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /v1/admin/audit-logs(설계 2026-08-01 §4) — adminId/targetUserId AND 필터 + 페이지네이션, at 내림차순. */
@RestController
public class AdminAuditLogController {

	private final AdminAuditLogRepository repository;

	public AdminAuditLogController(AdminAuditLogRepository repository) {
		this.repository = repository;
	}

	@GetMapping("/v1/admin/audit-logs")
	public ApiResponse<List<AdminAuditLogEntry>> list(@RequestParam(required = false) Long adminId,
			@RequestParam(required = false) Long targetUserId, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer limit) {
		AdminPageRequest pageRequest = AdminPageRequest.of(page, limit);
		AdminAuditLogRepository.Page result =
				repository.findPage(adminId, targetUserId, pageRequest.limit(), pageRequest.offset());
		List<AdminAuditLogEntry> entries = result.rows().stream().map(AdminAuditLogEntry::from).toList();

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", result.total());
		meta.put("limit", pageRequest.limit());
		meta.put("offset", pageRequest.offset());
		return ApiResponse.ok(entries, meta);
	}
}
