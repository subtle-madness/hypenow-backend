package com.celfit.was.v1.content;

import java.math.BigDecimal;
import java.util.List;

/**
 * 스펙 5.3 공통 Content 카드 — 6.1 리더보드 목록·6.4 인플루언서 최근 콘텐츠가 재사용한다.
 * 날짜 규약(스펙 3.4): postedAt=KST 달력 날짜(YYYY-MM-DD), updatedAt=ISO 8601 UTC(Z).
 * 개인화 필드(isContentsSaved)는 P2 전이라 record에 없음(비로그인=필드 부재가 스펙 규약).
 */
public record ContentCard(
		String id,
		String thumbnailUrl,
		String caption,
		String postedAt,
		String contentType,
		String mainCategory,
		List<String> subCategories,
		String adType,
		BigDecimal videoDuration,
		String originalUrl,
		String updatedAt,
		Long hypeScore,
		Long views,
		Long likes,
		Long comments,
		List<String> brands,
		List<String> products,
		List<String> distributors,
		Influencer influencer) {

	public record Influencer(String id, String handle, String displayName, String profileImageUrl, Long followers) {
	}
}
