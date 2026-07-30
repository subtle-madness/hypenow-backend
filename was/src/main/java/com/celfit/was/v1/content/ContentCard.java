package com.celfit.was.v1.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * 스펙 5.3 공통 Content 카드 — 6.1 리더보드 목록·6.4 인플루언서 최근 콘텐츠가 재사용한다.
 * 날짜 규약(스펙 3.4): postedAt=KST 달력 날짜(YYYY-MM-DD), updatedAt=ISO 8601 UTC(Z).
 * 개인화 필드(isContentsSaved, 마지막 컴포넌트): 스펙 2절 Optional 규약 — 로그인 시에만 true/false,
 * 비로그인이면 null로 두어 @JsonInclude(NON_NULL)이 키 자체를 생략한다(null이 아니라 키 부재).
 * hypeScore: 2026-07-30부터 소수(BigDecimal) — contents.hype_score_precise(출력 매핑 적용, 소수
 * 4자리) 값을 그대로 싣는다(스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md §10).
 * 자리수 조정은 프론트 몫 — 여기서 반올림하지 않는다.
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
		BigDecimal hypeScore,
		Long views,
		Long likes,
		Long comments,
		List<String> brands,
		List<String> products,
		List<String> distributors,
		Influencer influencer,
		@JsonInclude(JsonInclude.Include.NON_NULL) Boolean isContentsSaved) {

	public record Influencer(String id, String handle, String displayName, String profileImageUrl, Long followers) {
	}
}
