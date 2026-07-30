package com.celfit.was.v1.influencer;

import java.math.BigDecimal;
import java.util.List;

/**
 * 스펙 6.21 발굴 목록 카드. 모든 파생 지표의 근거는 최근 12개 게시물 창(account_content_series).
 * id는 handle 그대로(6.4 확정 준용). email은 biography 정규식 파싱(V46, 스펙
 * 2026-07-30-influencer-email-from-bio) — 매치 없으면 null.
 * effectiveFollowers·avgViews·er는 산출 불가(ER·릴스 없음)면 null, bio·tagline 부재는 빈 문자열.
 * hypeScore: 계정 하입 스코어(최근창 콘텐츠 hype_score 단순 평균, 0~100) — 점수 가능 콘텐츠 없으면 null
 * (스펙 2026-07-29-influencer-avg-hype-score).
 */
public record InfluencerCard(String id, String handle, String displayName, String profileImageUrl,
		Long followers, Long effectiveFollowers, Long postsCount, Long followingCount, String bio,
		String email, String tagline, BigDecimal reachMultiplier, BigDecimal er, Long avgViews,
		Long avgLikes, Long avgComments, Integer hypeScore, Long sponsoredCount,
		List<String> collaboratedBrands, List<CategoryShare> categoryShares,
		List<RecentThumb> recentThumbs) {

	/** 대분류 비중 상위 최대 3개 — category는 5.5 슬러그, pct는 0~100 정수(분모 = 창 내 분류 게시물). */
	public record CategoryShare(String category, Integer pct) {
	}

	/** 카드 하단 썸네일 + hover 성과 툴팁 소스, postedAt 내림차순 최대 4개. 피드 views는 null(3.6). */
	public record RecentThumb(String contentId, String thumbnailUrl, String contentType,
			String mainCategory, String adType, String postedAt, Long views, Long likes,
			Long comments) {
	}
}
