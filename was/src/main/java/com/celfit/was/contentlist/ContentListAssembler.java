package com.celfit.was.contentlist;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 목록 행 → 카드 응답 조립. ER·경과일 계산 헬퍼는 PostDetailAssembler와 동일 시맨틱의
 * 의도적 소규모 중복이다(4자리 HALF_UP, 24h 단위 — 공용 util 신설은 §4-4 취지상 보류, 태스크 H 계획 참고).
 */
@Component
public class ContentListAssembler {

	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};

	private final ObjectMapper objectMapper;

	public ContentListAssembler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public ContentListResponse toResponse(long totalCount, List<ContentListRow> rows, OffsetDateTime reference) {
		return new ContentListResponse(totalCount, rows.stream().map(row -> toItem(row, reference)).toList());
	}

	private ContentListResponse.Item toItem(ContentListRow row, OffsetDateTime reference) {
		return new ContentListResponse.Item(
				row.shortCode(), row.thumbnailUrl(), row.caption(),
				row.postedAt(), daysSincePosted(row.postedAt(), reference), row.contentType(),
				new ContentListResponse.Item.Account(
						row.handle(), row.displayName(), row.profileImageUrl(), row.followers()),
				row.views(), row.likes(), row.comments(),
				engagementRate(row.views(), row.likes(), row.comments()), row.hypeScore(), row.capturedAt(),
				row.adType(), parse(row.productCategoriesJson()), row.brandCount());
	}

	/** jsonb 원문을 문자열 리스트로 — null(VLM 미실행)은 null 그대로 전달. */
	private List<String> parse(String json) {
		if (json == null) {
			return null;
		}
		return objectMapper.readValue(json, STRING_LIST);
	}

	/** 경과일 = 24시간 단위 경과 수 (PostDetailAssembler와 동일 시맨틱). */
	private Long daysSincePosted(OffsetDateTime postedAt, OffsetDateTime reference) {
		if (postedAt == null) {
			return null;
		}
		return ChronoUnit.DAYS.between(postedAt, reference);
	}

	/** (좋아요+댓글)/조회수 4자리 HALF_UP — 피드는 조회수가 항상 NULL이라 참여율도 null. */
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
