package com.celfit.was.v1.admin;

import com.celfit.was.entitlement.OrganizationRepository;
import com.celfit.was.entitlement.OrganizationRepository.OrganizationRow;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 조직 관리(계획 Task 3, 설계 2026-08-17 §어드민 API) — /v1/admin/organizations CRUD +
 * 멤버 배정·역할 변경·해지 + 기능 오버라이드. 인가는 SecurityConfig(hasRole ADMIN, /v1/admin/**)가 처리
 * (AdminUsersController·AdminNoticesController 관례), 이 컨트롤러는 쓰기·조회 로직만 담당한다.
 */
@RestController
public class AdminOrganizationsController {

	private final OrganizationRepository organizationRepository;
	private final OrganizationAdminService adminService;

	public AdminOrganizationsController(OrganizationRepository organizationRepository,
			OrganizationAdminService adminService) {
		this.organizationRepository = organizationRepository;
		this.adminService = adminService;
	}

	@PostMapping("/v1/admin/organizations")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<OrganizationResponse> create(@RequestBody OrganizationCreateRequest request) {
		long id = adminService.create(request);
		return ApiResponse.ok(OrganizationResponse.from(adminService.requireById(id)));
	}

	/** 목록(AdminUsersController.list 관용구 — page/limit, sort 없음). */
	@GetMapping("/v1/admin/organizations")
	public ApiResponse<List<OrganizationResponse>> list(@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer limit) {
		AdminPageRequest pageRequest = AdminPageRequest.of(page, limit);
		OrganizationRepository.Page result = organizationRepository.findPage(pageRequest.limit(), pageRequest.offset());
		List<OrganizationResponse> rows = result.rows().stream().map(OrganizationResponse::from).toList();

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", result.total());
		meta.put("limit", pageRequest.limit());
		meta.put("offset", pageRequest.offset());
		return ApiResponse.ok(rows, meta);
	}

	@GetMapping("/v1/admin/organizations/{id}")
	public ApiResponse<OrganizationDetailResponse> detail(@PathVariable String id) {
		long orgId = parseOrgId(id);
		OrganizationRow row = adminService.requireById(orgId);
		return ApiResponse.ok(OrganizationDetailResponse.from(row, organizationRepository.findMembers(orgId),
				organizationRepository.findOverrides(orgId)));
	}

	@PatchMapping("/v1/admin/organizations/{id}")
	public ApiResponse<OrganizationResponse> patch(@PathVariable String id, @RequestBody Map<String, Object> body) {
		OrganizationRow row = adminService.patch(parseOrgId(id), body);
		return ApiResponse.ok(OrganizationResponse.from(row));
	}

	@PostMapping("/v1/admin/organizations/{id}/members")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<OrganizationMemberResponse> addMember(@PathVariable String id,
			@RequestBody OrganizationMemberAddRequest request) {
		long orgId = parseOrgId(id);
		adminService.addMember(orgId, request);
		OrganizationRepository.MemberRow member = organizationRepository.findMember(orgId, request.userId())
				.orElseThrow(() -> new IllegalStateException("멤버 배정 직후 조회 실패 — orgId=" + orgId));
		return ApiResponse.ok(OrganizationMemberResponse.from(member));
	}

	@PatchMapping("/v1/admin/organizations/{id}/members/{userId}")
	public ApiResponse<OrganizationMemberResponse> updateMemberRole(@PathVariable String id,
			@PathVariable String userId, @RequestBody OrganizationMemberRoleRequest request) {
		long orgId = parseOrgId(id);
		long targetUserId = parseUserId(userId);
		adminService.updateMemberRole(orgId, targetUserId, request);
		OrganizationRepository.MemberRow member = organizationRepository.findMember(orgId, targetUserId)
				.orElseThrow(() -> new IllegalStateException("역할 변경 직후 조회 실패 — orgId=" + orgId));
		return ApiResponse.ok(OrganizationMemberResponse.from(member));
	}

	@DeleteMapping("/v1/admin/organizations/{id}/members/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeMember(@PathVariable String id, @PathVariable String userId) {
		adminService.removeMember(parseOrgId(id), parseUserId(userId));
	}

	@PutMapping("/v1/admin/organizations/{id}/overrides/{featureKey}")
	public ApiResponse<OrganizationOverrideResponse> upsertOverride(@PathVariable String id,
			@PathVariable String featureKey, @RequestBody OrganizationOverrideRequest request) {
		long orgId = parseOrgId(id);
		adminService.upsertOverride(orgId, featureKey, request);
		OrganizationRepository.OverrideRow override = organizationRepository.findOverrides(orgId).stream()
				.filter(row -> row.featureKey().equals(featureKey))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("오버라이드 저장 직후 조회 실패 — orgId=" + orgId));
		return ApiResponse.ok(OrganizationOverrideResponse.from(override));
	}

	@DeleteMapping("/v1/admin/organizations/{id}/overrides/{featureKey}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteOverride(@PathVariable String id, @PathVariable String featureKey) {
		adminService.deleteOverride(parseOrgId(id), featureKey);
	}

	/** id는 문자열 path 파라미터라 숫자가 아니면 존재할 수 없는 id → 404(AdminUsersController.parseId 관례). */
	private static long parseOrgId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw V1ApiException.notFound("조직을 찾을 수 없습니다.");
		}
	}

	private static long parseUserId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw V1ApiException.notFound("소속 멤버를 찾을 수 없습니다.");
		}
	}
}
