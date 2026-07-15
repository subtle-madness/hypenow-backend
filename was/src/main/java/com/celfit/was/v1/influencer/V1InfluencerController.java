package com.celfit.was.v1.influencer;

import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.content.ContentCardAssembler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 6.4 인플루언서 프로필 + 최근 12개 — influencerId는 handle 그대로(설계 확정). */
@RestController
public class V1InfluencerController {

	private final V1InfluencerRepository repository;
	private final ContentCardAssembler assembler;

	public V1InfluencerController(V1InfluencerRepository repository, ContentCardAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@GetMapping("/v1/influencers/{influencerId}")
	public ApiResponse<InfluencerProfileResponse> influencer(@PathVariable String influencerId) {
		V1InfluencerRepository.ProfileRow row = repository.findProfile(influencerId)
				.orElseThrow(() -> V1ApiException.notFound("인플루언서를 찾을 수 없습니다."));

		InfluencerProfileResponse.Influencer influencer = new InfluencerProfileResponse.Influencer(
				row.handle(), row.handle(), row.displayName(), row.profileImageUrl(),
				row.followers(), row.postsCount(), row.followsCount(), row.biography(),
				null, row.externalLink());

		var recentContents = repository.findRecentCards(influencerId).stream()
				.map(assembler::toCard).toList();

		return ApiResponse.ok(new InfluencerProfileResponse(influencer, recentContents));
	}
}
