package com.celfit.was.v1.influencer;

import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import java.util.List;
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

	/** 유사 인플루언서 — traits·카테고리 겹침 휴리스틱(07-27 확정, 임베딩 아님).
	 *  matchPct 계산은 조립 규칙 한 줄이라 Assembler 불요, 컨트롤러 인라인.
	 *  기준 계정이 없거나(피어/카피 미존재) 후보가 없으면 빈 목록 — aiReport의 404와 달리
	 *  이 표면은 200+[](패널 데이터라 부재=빈 패널, findSimilar 참조). */
	@GetMapping("/v1/influencers/{influencerId}/similar")
	public ApiResponse<List<SimilarInfluencer>> similar(@PathVariable String influencerId) {
		return ApiResponse.ok(repository.findSimilar(influencerId).stream()
				// overlapN·unionN 둘 다 SQL count(DISTINCT ...) 결과라 null일 수 없다(COUNT는
				// 항상 0 이상 반환) — null 체크 불요, 0 여부만 확인.
				.map(r -> new SimilarInfluencer(r.influencerId(), r.name(), r.profileImageUrl(),
						r.tagline(), r.overlapN() > 0 && r.unionN() > 0
								? Math.toIntExact(Math.round(r.overlapN() * 100.0 / r.unionN()))
								: null))
				.toList());
	}
}
