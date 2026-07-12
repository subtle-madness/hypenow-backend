package com.celfit.was.postdetail;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;

/** 계약 record → 모달 블록 응답 조립. 행 단위 파생값(참여율·경과일)만 계산한다(§4-2 표현 조립). */
@Component
public class PostDetailAssembler {

	private final Clock clock;

	public PostDetailAssembler(Clock clock) {
		this.clock = clock;
	}

	public PostDetailResponse toResponse(Content content, Account account, List<ContentComment> comments) {
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
										c.id(), c.authorMasked(), c.body(), c.likeCount()))
								.toList()));
	}

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
