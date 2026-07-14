package com.celfit.was.contentlist;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** 랭킹 목록 응답 — 프론트 URL 파라미터 계약(§7 2026-07-14)의 응답 계약 그대로. */
public record ContentListResponse(long totalCount, List<Item> items) {

	/**
	 * engagementRate = (likes+comments)/views — views가 NULL(피드)이거나 0이면 null.
	 * metricsCapturedAt·adType·productCategories·brandCount는 as-of 스냅샷·VLM 산출 — 미실행/부재면 null.
	 */
	public record Item(
			String shortCode,
			String thumbnailUrl,
			String caption,
			OffsetDateTime postedAt,
			Long daysSincePosted,
			String contentType,
			Account account,
			Long views,
			Long likes,
			Long comments,
			BigDecimal engagementRate,
			Long hypeScore,
			OffsetDateTime metricsCapturedAt,
			String adType,
			List<String> productCategories,
			Integer brandCount) {

		public record Account(String handle, String displayName, String profileImageUrl, Long followers) {
		}
	}
}
