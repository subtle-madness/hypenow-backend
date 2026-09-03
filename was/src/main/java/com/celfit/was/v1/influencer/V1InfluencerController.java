package com.celfit.was.v1.influencer;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.SavedLookup;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.content.ContentCardAssembler;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 6.4 인플루언서 프로필 + 최근 12개 — influencerId는 handle 그대로(설계 확정).
 *
 * <p>인증은 <b>Required</b>다(SecurityConfig — 발굴 목록 6.21만 비로그인 공개, 상세는 잠김 유지).
 * FE api-spec 6.4의 "인증: Optional" 표기는 스펙 오기이며 구현이 의도다(2026-09-03 FE 피드백 회신).
 * 아래 principal null 분기는 그 잠금이 풀릴 경우를 대비한 Optional 규약(스펙 2절 — 비로그인이면
 * isContentsSaved·isInfluencerSaved 필드 부재)이라 그대로 둔다.
 */
@RestController
public class V1InfluencerController {

	private final V1InfluencerRepository repository;
	private final ContentCardAssembler assembler;
	private final SavedLookup savedLookup;

	public V1InfluencerController(V1InfluencerRepository repository, ContentCardAssembler assembler,
			SavedLookup savedLookup) {
		this.repository = repository;
		this.assembler = assembler;
		this.savedLookup = savedLookup;
	}

	@GetMapping("/v1/influencers/{influencerId}")
	public ApiResponse<InfluencerProfileResponse> influencer(
			@AuthenticationPrincipal AppUserDetails principal, @PathVariable String influencerId) {
		V1InfluencerRepository.ProfileRow row = repository.findProfile(influencerId)
				.orElseThrow(() -> V1ApiException.notFound("인플루언서를 찾을 수 없습니다."));

		// 로그인 시에만 개인화: 저장 셋 1회 조회 + 인플루언서 저장 여부. 비로그인이면 둘 다 null(필드 부재).
		Set<String> savedCodes = principal == null ? null : savedLookup.savedShortCodes(principal.getUserId());
		Boolean influencerSaved = principal == null ? null
				: savedLookup.isInfluencerSaved(principal.getUserId(), row.handle());

		InfluencerProfileResponse.Influencer influencer = new InfluencerProfileResponse.Influencer(
				row.handle(), row.handle(), row.displayName(), row.profileImageUrl(),
				row.followers(), row.postsCount(), row.followsCount(), row.biography(),
				row.email(), row.externalLink());

		var recentContents = repository.findRecentCards(influencerId).stream()
				.map(r -> assembler.toCard(r, savedCodes == null ? null : savedCodes.contains(r.shortCode())))
				.toList();

		return ApiResponse.ok(new InfluencerProfileResponse(influencer, recentContents, influencerSaved));
	}
}
