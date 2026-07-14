package com.celfit.was.postdetail;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 계약 record·로컬 행 → 모달 블록 응답 조립. 행 단위 파생값(참여율·경과일) 계산과 jsonb 파싱만 한다(§4-2 표현 조립). */
@Component
public class PostDetailAssembler {

	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};
	private static final TypeReference<List<PostDetailResponse.Analysis.Content.Brand>> BRAND_LIST =
			new TypeReference<>() {
	};
	private static final TypeReference<List<PostDetailResponse.Analysis.Content.Attribute>> ATTRIBUTE_LIST =
			new TypeReference<>() {
	};

	private final ObjectMapper objectMapper;
	private final Clock clock;

	public PostDetailAssembler(ObjectMapper objectMapper, Clock clock) {
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public PostDetailResponse toResponse(Content content, Account account,
			List<CommentRow> comments, Optional<ContentAnalysisRow> analysis) {
		return new PostDetailResponse(
				new PostDetailResponse.Post(
						content.shortCode(), content.thumbnailUrl(), content.caption(),
						content.postedAt(), daysSincePosted(content.postedAt()),
						content.contentType(), content.videoDuration(), content.originalUrl(),
						content.views(), content.likes(), content.comments(),
						engagementRate(content), content.hypeScore()),
				account == null ? null
						: new PostDetailResponse.Account(
								account.handle(), account.displayName(),
								account.profileImageUrl(), account.followers()),
				new PostDetailResponse.Comments(
						comments.size(),
						comments.stream()
								.map(c -> new PostDetailResponse.Comments.Item(
										c.id(), c.authorMasked(), c.body(), c.likeCount(), c.aiCategory()))
								.toList()),
				analysis.map(this::toAnalysis).orElse(null));
	}

	private PostDetailResponse.Analysis toAnalysis(ContentAnalysisRow row) {
		return new PostDetailResponse.Analysis(
				row.analyzedAt(),
				row.aiContentSummary(), row.contentsPattern(), row.aiCommentInsight(),
				new PostDetailResponse.Analysis.Baseline(
						row.recentReelsAvgViews(), row.rankInRecentReels(), row.recentReelsCount(),
						row.recentContentsCount(), row.recent12AvgEngagementRate(),
						row.recent12AvgLikeCount(), row.recent12AvgCommentCount()),
				new PostDetailResponse.Analysis.CategoryContext(
						row.categoryTopPercentile(), row.categoryAvgViews(), row.categorySampleSize()),
				new PostDetailResponse.Analysis.Content(
						parse(row.detectedBrandsJson(), BRAND_LIST),
						row.sponsoredSignalLevel(),
						parse(row.sponsoredSignalReasonsJson(), STRING_LIST),
						row.adDisclosure(),
						parse(row.detectedProductCategoriesJson(), STRING_LIST),
						parse(row.vlmAttributesJson(), ATTRIBUTE_LIST),
						row.mainCategory(),
						parse(row.subCategoriesJson(), STRING_LIST),
						row.adType()),
				new PostDetailResponse.Analysis.CommentAuthenticity(
						row.commentAuthenticityGrade(), row.commentAuthenticityNote()));
	}

	/** jsonb 원문을 응답 구조로 — null(VLM 미실행)은 null 그대로 전달. */
	private <T> T parse(String json, TypeReference<T> type) {
		if (json == null) {
			return null;
		}
		return objectMapper.readValue(json, type);
	}

	/** 경과일 = 24시간 단위 경과 수 (캘린더 날짜 경계 아님 — 게시 23시간 후는 0). 프론트 "게시 N일차" = 이 값 + 1. */
	private Long daysSincePosted(OffsetDateTime postedAt) {
		if (postedAt == null) {
			return null;
		}
		return ChronoUnit.DAYS.between(postedAt, OffsetDateTime.now(clock));
	}

	/** (좋아요+댓글)/조회수 — 피드는 조회수가 항상 NULL이라 참여율도 null (조회수 NULL 규칙). */
	private BigDecimal engagementRate(Content content) {
		if (content.views() == null || content.views() == 0) {
			return null;
		}
		long engagements = nullToZero(content.likes()) + nullToZero(content.comments());
		return BigDecimal.valueOf(engagements)
				.divide(BigDecimal.valueOf(content.views()), 4, RoundingMode.HALF_UP);
	}

	private long nullToZero(Long value) {
		return value == null ? 0 : value;
	}
}
