package com.celfit.was.v1.influencer;

import com.celfit.was.v1.content.ContentCard;
import java.util.List;

/** 스펙 6.4 — email은 파이프라인 미수집으로 항상 null(회신표 #2), externalLink는 accounts 미러 값. */
public record InfluencerProfileResponse(Influencer influencer, List<ContentCard> recentContents) {

	public record Influencer(String id, String handle, String displayName, String profileImageUrl,
			Long followers, Long postsCount, Long followingCount, String bio,
			String email, String externalLink) {
	}
}
