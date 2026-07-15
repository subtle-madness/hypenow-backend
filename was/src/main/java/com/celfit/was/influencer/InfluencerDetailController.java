package com.celfit.was.influencer;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 인플루언서 상세 API — C1 미러 3종(계약 record) + accounts를 조합해 1회 호출로 서빙한다. */
@RestController
public class InfluencerDetailController {

	private final InfluencerDetailRepository repository;
	private final InfluencerDetailAssembler assembler;

	public InfluencerDetailController(InfluencerDetailRepository repository, InfluencerDetailAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@GetMapping("/api/influencers/{handle}")
	public InfluencerDetailResponse influencerDetail(@PathVariable String handle) {
		return repository.findSummary(handle)
				.map(summary -> assembler.toResponse(
						summary,
						repository.findAccount(handle).orElse(null),
						repository.findCategoryStats(handle),
						repository.findSeries(handle)))
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "인플루언서를 찾을 수 없습니다: " + handle));
	}
}
