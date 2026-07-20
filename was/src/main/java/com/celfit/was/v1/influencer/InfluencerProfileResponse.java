package com.celfit.was.v1.influencer;

import com.celfit.was.v1.content.ContentCard;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 스펙 6.4 — email은 파이프라인 미수집으로 항상 null(회신표 #2), externalLink는 accounts 미러 값.
 * isInfluencerSaved(마지막 필드): 스펙 2절 Optional 규약 — 로그인 시에만 true/false, 비로그인이면 null로 두어
 * @JsonInclude(NON_NULL)이 키 자체를 생략한다.
 */
public record InfluencerProfileResponse(Influencer influencer, List<ContentCard> recentContents,
		@JsonInclude(JsonInclude.Include.NON_NULL) Boolean isInfluencerSaved) {

	public record Influencer(String id, String handle, String displayName, String profileImageUrl,
			Long followers, Long postsCount, Long followingCount, String bio,
			String email, String externalLink) {
	}
}
