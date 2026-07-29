package com.celfit.was.v1.influencer;

import com.celfit.was.v1.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 6.5 인플루언서 AI 리포트 — influencerId는 handle 그대로(6.4와 동일 설계). 조립·캐시는 서비스. */
@RestController
public class V1InfluencerReportController {

	private final V1InfluencerReportService service;

	public V1InfluencerReportController(V1InfluencerReportService service) {
		this.service = service;
	}

	@GetMapping("/v1/influencers/{influencerId}/ai-report")
	public ApiResponse<InfluencerAiReport> aiReport(@PathVariable String influencerId) {
		return ApiResponse.ok(service.report(influencerId));
	}
}
