package com.celfit.was.v1.influencer;

import com.celfit.was.v1.content.ContentCard;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 스펙 6.4 — email은 account_summaries.email(소개글 정규식 파싱, analytics V46 — 목록 6.21·유사 6.23과
 * 같은 소스, 미검출이면 null), externalLink는 accounts 미러 값. 회신표 #2의 "미수집이라 항상 null"은
 * 2026-07-30 이메일 발굴 도입으로 해소됐는데 상세만 null 상수로 남아 있던 결함을 2026-09-03에 수리.
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
