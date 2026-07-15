package com.celfit.was.postdetail;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentMetricSnapshot;
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

	public PostDetailResponse toResponse(Content content, Account account, List<CommentRow> comments,
			Optional<ContentAnalysisRow> analysis, Optional<AsOfMetrics> asOf) {
		return new PostDetailResponse(
				asOf.map(a -> asOfPost(content, a)).orElseGet(() -> latestPost(content)),
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

	/** 최신 경로 — contents 미러 값 + 현재 시각 기준 경과일. */
	private PostDetailResponse.Post latestPost(Content content) {
		return post(content, content.views(), content.likes(), content.comments(), content.hypeScore(),
				OffsetDateTime.now(clock), null);
	}

	/** as-of 경로 — 스냅샷 지표 + 기간 끝 기준 경과일. 메타(캡션·썸네일 등)는 최신 미러 값. */
	private PostDetailResponse.Post asOfPost(Content content, AsOfMetrics asOf) {
		ContentMetricSnapshot s = asOf.snapshot();
		return post(content, s.views(), s.likes(), s.comments(), s.hypeScore(),
				asOf.reference(), s.capturedAt());
	}

	private PostDetailResponse.Post post(Content content, Long views, Long likes, Long comments,
			Long hypeScore, OffsetDateTime reference, OffsetDateTime metricsCapturedAt) {
		return new PostDetailResponse.Post(
				content.shortCode(), content.thumbnailUrl(), content.caption(),
				content.postedAt(), daysSincePosted(content.postedAt(), reference),
				content.contentType(), content.videoDuration(), content.originalUrl(),
				views, likes, comments,
				engagementRate(views, likes, comments), hypeScore, metricsCapturedAt);
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

	/**
	 * 경과일 = 24시간 단위 경과 수 (캘린더 날짜 경계 아님 — 게시 23시간 후는 0). 프론트 "게시 N일차" = 이 값 + 1.
	 * reference = 최신 경로는 현재 시각, as-of 경로는 집계 기간 끝 기준 시각.
	 */
	private Long daysSincePosted(OffsetDateTime postedAt, OffsetDateTime reference) {
		if (postedAt == null) {
			return null;
		}
		return ChronoUnit.DAYS.between(postedAt, reference);
	}

	/** (좋아요+댓글)/조회수 — 피드는 조회수가 항상 NULL이라 참여율도 null (조회수 NULL 규칙). */
	private BigDecimal engagementRate(Long views, Long likes, Long comments) {
		if (views == null || views == 0) {
			return null;
		}
		long engagements = nullToZero(likes) + nullToZero(comments);
		return BigDecimal.valueOf(engagements)
				.divide(BigDecimal.valueOf(views), 4, RoundingMode.HALF_UP);
	}

	private long nullToZero(Long value) {
		return value == null ? 0 : value;
	}
}
