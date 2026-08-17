package com.celfit.was.v1.org;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 조직 셀프서비스(계획 Task 4, 설계 2026-08-17 §조직 셀프서비스) — /v1/org/**. 인증 필수(SecurityConfig
 * anyRequest().authenticated()에 이미 포섭 — /v1/admin/**처럼 role 게이트가 없다, role 무관 대신
 * OrgService가 요청마다 DB 멤버십을 재해석해 조직·ORG_ADMIN 스코프를 가른다).
 */
@RestController
public class OrgController {

	private final OrgService orgService;

	public OrgController(OrgService orgService) {
		this.orgService = orgService;
	}

	@GetMapping("/v1/org")
	public ApiResponse<OrgResponse> get(@AuthenticationPrincipal AppUserDetails principal) {
		return ApiResponse.ok(orgService.getOrg(principal.getUserId()));
	}

	@GetMapping("/v1/org/members")
	public ApiResponse<List<OrgMemberResponse>> members(@AuthenticationPrincipal AppUserDetails principal) {
		List<OrgMemberResponse> rows = orgService.listMembers(principal.getUserId());
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", rows.size());
		return ApiResponse.ok(rows, meta);
	}

	@PostMapping("/v1/org/members")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<OrgMemberResponse> addMember(@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody OrgMemberAddRequest request) {
		return ApiResponse.ok(orgService.addMember(principal.getUserId(), request));
	}

	@PatchMapping("/v1/org/members/{userId}")
	public ApiResponse<OrgMemberResponse> updateMemberRole(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String userId, @RequestBody OrgMemberRoleRequest request) {
		return ApiResponse.ok(orgService.updateMemberRole(principal.getUserId(), parseUserId(userId), request));
	}

	@DeleteMapping("/v1/org/members/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeMember(@AuthenticationPrincipal AppUserDetails principal, @PathVariable String userId) {
		orgService.removeMember(principal.getUserId(), parseUserId(userId));
	}

	/** id는 문자열 path 파라미터라 숫자가 아니면 존재할 수 없는 id → 404(AdminUsersController.parseId 관례). */
	private static long parseUserId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw V1ApiException.notFound("소속 멤버를 찾을 수 없습니다.");
		}
	}
}
