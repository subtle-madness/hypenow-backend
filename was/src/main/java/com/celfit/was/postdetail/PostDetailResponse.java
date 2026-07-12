package com.celfit.was.postdetail;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 콘텐츠 상세 모달 응답. 블록이 소스 테이블과 1:1 대응한다(post=contents, account=accounts,
 * comments=content_comments). LLM 산출 블록(AI 요약·성과 비교·카테고리 맥락·콘텐츠 분석·댓글 분석,
 * 댓글 ai_category)은 필드 자체가 없고 B2·B3 완료 후 additive로 추가된다.
 */
public record PostDetailResponse(Post post, Account account, Comments comments) {

	/** engagementRate = (likes+comments)/views — views가 NULL(피드)이거나 0이면 null. */
	public record Post(
			String shortCode,
			String thumbnailUrl,
			String caption,
			OffsetDateTime postedAt,
			Long daysSincePosted,
			String contentType,
			BigDecimal videoDuration,
			String originalUrl,
			Long views,
			Long likes,
			Long comments,
			BigDecimal engagementRate,
			Long hypeScore) {
	}

	public record Account(
			String handle,
			String displayName,
			String profileImageUrl,
			Long followers) {
	}

	/** collectedCount = 수집 댓글 수(작성자 답글은 수집 안 함) — post.comments(원 지표)와 다른 값. */
	public record Comments(int collectedCount, List<Item> items) {

		public record Item(Long id, String authorMasked, String body, Long likeCount) {
		}
	}
}
