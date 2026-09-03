package com.celfit.was.v1.admin;

import com.celfit.was.v1.common.ApiResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /v1/admin/brand-monitoring/accounts(2026-09-03) — 어드민 "등록된 브랜드 목록" 표. 인가는
 * SecurityConfig(hasRole ADMIN, {@code /v1/admin/**})가 처리, 이 컨트롤러는 조회·페이지네이션만
 * 담당한다. 계약 상세는 docs/contracts/admin-brand-monitoring-accounts-api.md 참조.
 */
@RestController
public class AdminBrandAccountsController {

	private final AdminBrandAccountService service;

	public AdminBrandAccountsController(AdminBrandAccountService service) {
		this.service = service;
	}

	/** offset이 오면 offset이 이긴다(page는 무시) — 둘 다 없으면 page=1 취급(AdminPageRequest.of). */
	@GetMapping("/v1/admin/brand-monitoring/accounts")
	public ApiResponse<List<AdminBrandAccountRow>> list(@RequestParam(required = false) Integer offset,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer limit,
			@RequestParam(required = false) String sort, @RequestParam(required = false) String q) {
		AdminPageRequest pageRequest =
				offset != null ? AdminPageRequest.ofOffset(offset, limit) : AdminPageRequest.of(page, limit);
		AdminBrandAccountService.Result result = service.list(pageRequest, sort, q);

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", result.total());
		meta.put("limit", pageRequest.limit());
		meta.put("offset", pageRequest.offset());
		return ApiResponse.ok(result.rows(), meta);
	}
}
