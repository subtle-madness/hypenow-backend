package com.celfit.was.postdetail;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 콘텐츠 상세 모달 응답. 블록이 소스 테이블과 1:1 대응한다(post=contents, account=accounts,
 * comments=content_comments). LLM 산출 블록: 댓글 aiCategory(B2)·analysis(B3) — 미분석이면 null.
 */
public record PostDetailResponse(Post post, Account account, Comments comments, Analysis analysis) {

	/**
	 * engagementRate = (likes+comments)/views — views가 NULL(피드)이거나 0이면 null.
	 * metricsCapturedAt = 지표가 어느 스냅샷에서 왔는지 — asOf 경로에서만 채워지고 최신 경로는 null.
	 */
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
			Long hypeScore,
			OffsetDateTime metricsCapturedAt) {
	}

	public record Account(
			String handle,
			String displayName,
			String profileImageUrl,
			Long followers) {
	}

	/** collectedCount = 수집 댓글 수(작성자 답글은 수집 안 함) — post.comments(원 지표)와 다른 값. */
	public record Comments(int collectedCount, List<Item> items) {

		/** 미분류 댓글은 aiCategory null. 어휘(purchase|question|positive|adAware|friendTag|etc)는 분석 층이 확정. */
		public record Item(Long id, String authorMasked, String body, Long likeCount, String aiCategory) {
		}
	}

	/** content_analyses 1행 (분석 시점 고정 스냅샷) — 미분석 콘텐츠면 블록 전체가 null. */
	public record Analysis(
			OffsetDateTime analyzedAt,
			String aiContentSummary,
			String contentsPattern,
			String aiCommentInsight,
			Baseline baseline,
			CategoryContext categoryContext,
			Content content,
			CommentAuthenticity commentAuthenticity) {

		/** 분석 시점의 계정 기준선 — 최신 미러와 다를 수 있다(의도: AI 텍스트가 참조한 수치와 동일 시점). */
		public record Baseline(
				Long recentReelsAvgViews,
				Integer rankInRecentReels,
				Integer recentReelsCount,
				Integer recentContentsCount,
				BigDecimal recent12AvgEngagementRate,
				Long recent12AvgLikeCount,
				Long recent12AvgCommentCount) {
		}

		public record CategoryContext(Integer topPercentile, Long avgViews, Long sampleSize) {
		}

		/** VLM 산출 — 미실행 항목은 null 그대로(빈 리스트로 뭉개지 않음). */
		public record Content(
				List<Brand> detectedBrands,
				String sponsoredSignalLevel,
				List<String> sponsoredSignalReasons,
				String adDisclosure,
				List<String> detectedProductCategories,
				List<Attribute> attributes,
				String mainCategory,
				List<String> subCategories,
				String adType) {

			public record Brand(String name, String evidence) {
			}

			public record Attribute(String label, String value) {
			}
		}

		public record CommentAuthenticity(String grade, String note) {
		}
	}
}
