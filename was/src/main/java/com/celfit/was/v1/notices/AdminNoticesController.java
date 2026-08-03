package com.celfit.was.v1.notices;

import com.celfit.was.notices.NoticeRepository;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 업데이트 소식 CRUD — POST·GET·PATCH·DELETE /v1/admin/notices(/{id}). 인가는
 * SecurityConfig(hasRole ADMIN, /v1/admin/**)가 처리, 이 컨트롤러는 쓰기·조회 로직만 담당한다
 * (AdminUsersController 관례). basePackages(com.celfit.was.v1, v2) 범위 안이라 V1ExceptionAdvice가
 * V1ApiException을 그대로 envelope로 잡는다.
 */
@RestController
public class AdminNoticesController {

	private final NoticeRepository noticeRepository;
	private final NoticeAdminService adminService;

	public AdminNoticesController(NoticeRepository noticeRepository, NoticeAdminService adminService) {
		this.noticeRepository = noticeRepository;
		this.adminService = adminService;
	}

	@PostMapping("/v1/admin/notices")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<NoticeResponse> create(@RequestBody NoticeUpsertRequest request) {
		long id = adminService.create(request);
		NoticeRepository.NoticeRow row = noticeRepository.findById(id)
				.orElseThrow(() -> V1ApiException.notFound("소식을 찾을 수 없습니다."));
		return ApiResponse.ok(NoticeResponse.from(row));
	}

	/** 예약분(미래 published_at)도 포함한다 — 유저 표면 GET /v1/notices와의 차이. */
	@GetMapping("/v1/admin/notices")
	public ApiResponse<List<NoticeResponse>> list() {
		List<NoticeResponse> items = noticeRepository.findAll().stream()
				.map(NoticeResponse::from)
				.toList();
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", items.size());
		return ApiResponse.ok(items, meta);
	}

	@PatchMapping("/v1/admin/notices/{id}")
	public ApiResponse<NoticeResponse> update(@PathVariable String id, @RequestBody NoticeUpsertRequest request) {
		long noticeId = parseId(id);
		adminService.update(noticeId, request);
		NoticeRepository.NoticeRow row = noticeRepository.findById(noticeId)
				.orElseThrow(() -> V1ApiException.notFound("소식을 찾을 수 없습니다."));
		return ApiResponse.ok(NoticeResponse.from(row));
	}

	@DeleteMapping("/v1/admin/notices/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		adminService.delete(parseId(id));
	}

	/** id는 문자열 path 파라미터라 숫자가 아니면 존재할 수 없는 id → 404(AdminUsersController.parseId 관례). */
	private static long parseId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw V1ApiException.notFound("소식을 찾을 수 없습니다.");
		}
	}
}
