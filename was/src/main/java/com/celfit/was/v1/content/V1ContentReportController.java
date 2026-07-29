package com.celfit.was.v1.content;

import com.celfit.was.v1.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 6.3 콘텐츠 AI 리포트 — 인증 Optional이나 P1은 비로그인 응답과 동일(개인화 필드 없음). 조립·캐시는 서비스. */
@RestController
public class V1ContentReportController {

	private final V1ContentReportService service;

	public V1ContentReportController(V1ContentReportService service) {
		this.service = service;
	}

	@GetMapping("/v1/contents/{contentId}/ai-report")
	public ApiResponse<ContentAiReport> aiReport(@PathVariable String contentId) {
		return ApiResponse.ok(service.report(contentId));
	}
}
