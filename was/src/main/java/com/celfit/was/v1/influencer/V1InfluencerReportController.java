package com.celfit.was.v1.influencer;

import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 6.5 인플루언서 AI 리포트 — influencerId는 handle 그대로(6.4와 동일 설계). */
@RestController
public class V1InfluencerReportController {

	private final V1InfluencerReportRepository repository;
	private final V1InfluencerReportAssembler assembler;

	public V1InfluencerReportController(V1InfluencerReportRepository repository,
			V1InfluencerReportAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@GetMapping("/v1/influencers/{influencerId}/ai-report")
	public ApiResponse<InfluencerAiReport> aiReport(@PathVariable String influencerId) {
		var summary = repository.findSummary(influencerId)
				.orElseThrow(() -> V1ApiException.notFound("인플루언서를 찾을 수 없습니다."));
		return ApiResponse.ok(assembler.toReport(summary,
				repository.findLatestCopy(influencerId).orElse(null),
				repository.findSeries(influencerId),
				repository.findCategories(influencerId),
				repository.findBrands(influencerId),
				repository.findProducts(influencerId),
				repository.findPeerStats(influencerId).orElse(null)));
	}
}
