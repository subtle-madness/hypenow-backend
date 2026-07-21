package com.celfit.was.v1.content;

import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 6.3 콘텐츠 AI 리포트 — 인증 Optional이나 P1은 비로그인 응답과 동일(개인화 필드 없음). */
@RestController
public class V1ContentReportController {

	private final V1ContentReportRepository repository;
	private final V1ContentReportAssembler assembler;

	public V1ContentReportController(V1ContentReportRepository repository,
			V1ContentReportAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@GetMapping("/v1/contents/{contentId}/ai-report")
	public ApiResponse<ContentAiReport> aiReport(@PathVariable String contentId) {
		var report = repository.findReport(contentId)
				.orElseThrow(() -> V1ApiException.notFound("콘텐츠를 찾을 수 없습니다."));
		// 카테고리 맥락은 대분류가 있을 때만 집계한다 (미분류면 비교 모수 자체가 정의되지 않음).
		var categoryContext = report.mainCategory() == null ? null
				: repository.findCategoryContext(report.mainCategory(), report.views());
		return ApiResponse.ok(assembler.toReport(report,
				repository.findRecentReels(report.accountHandle()),
				categoryContext,
				repository.countByCategory(contentId),
				repository.findComments(contentId)));
	}
}
