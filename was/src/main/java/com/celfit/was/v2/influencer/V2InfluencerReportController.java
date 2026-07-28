package com.celfit.was.v2.influencer;

import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.influencer.InfluencerCard;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryAssembler;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 스펙 6.22·6.23 발굴 리포트 v2 — influencerId는 handle 그대로(6.4와 동일 설계).
 * 6.5(v1)는 기존 패널이 소비 중이라 병존 — 프론트 v2 전환 후 별도 PR로 폐기.
 * 인증은 둘 다 공개(SecurityConfig permitAll) — 잠금 표현은 프론트 처리(스펙 7절 15번).
 */
@RestController
public class V2InfluencerReportController {

	private final V2InfluencerReportRepository repository;
	private final V2InfluencerReportAssembler assembler;
	private final V1InfluencerDiscoveryRepository discoveryRepository;
	private final V1InfluencerDiscoveryAssembler discoveryAssembler;

	public V2InfluencerReportController(V2InfluencerReportRepository repository,
			V2InfluencerReportAssembler assembler,
			V1InfluencerDiscoveryRepository discoveryRepository,
			V1InfluencerDiscoveryAssembler discoveryAssembler) {
		this.repository = repository;
		this.assembler = assembler;
		this.discoveryRepository = discoveryRepository;
		this.discoveryAssembler = discoveryAssembler;
	}

	@GetMapping("/v2/influencers/{influencerId}/ai-report")
	public ApiResponse<InfluencerAiReportV2> aiReport(@PathVariable String influencerId) {
		var summary = repository.findSummary(influencerId)
				.orElseThrow(() -> V1ApiException.notFound("인플루언서를 찾을 수 없습니다."));
		// 신 스키마 카피 없으면 "리포트 미생성" — tagline·요약 3종이 비-null 계약이라 부분 응답 불가(스펙 6.22 에러)
		var copy = repository.findLatestCopy(influencerId)
				.orElseThrow(() -> V1ApiException.notFound("리포트가 아직 생성되지 않았습니다."));
		return ApiResponse.ok(assembler.toReport(summary, copy,
				repository.findSeries(influencerId),
				repository.findCategories(influencerId),
				repository.findBrandCollabs(influencerId)));
	}

	/** 6.23 — 응답은 6.21 InfluencerCard 재사용, 서버 고정 최대 10(유사도 내림차순, 07-28 유사도 v2 — 9→10 변경). */
	@GetMapping("/v2/influencers/{influencerId}/similar")
	public ApiResponse<List<InfluencerCard>> similar(@PathVariable String influencerId) {
		if (repository.findSummary(influencerId).isEmpty()) {
			throw V1ApiException.notFound("인플루언서를 찾을 수 없습니다.");
		}
		List<String> handles = repository.findSimilarHandles(influencerId);
		List<InfluencerCard> cards = discoveryAssembler.toCards(
				discoveryRepository.findCardsByHandles(handles),
				discoveryRepository.findShares(handles),
				discoveryRepository.findBrands(handles),
				discoveryRepository.findThumbs(handles),
				discoveryRepository.findEngagements(handles));
		// 카드 조회는 순서 비보장 — 유사도 순(handles) 복원
		Map<String, InfluencerCard> byId = cards.stream()
				.collect(Collectors.toMap(InfluencerCard::id, Function.identity()));
		return ApiResponse.ok(handles.stream().map(byId::get).filter(Objects::nonNull).toList());
	}
}
