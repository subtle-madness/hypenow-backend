package com.celfit.was.contentlist;

import java.time.OffsetDateTime;

/**
 * 랭킹 목록 조인 결과 1행 (contents ⋈ accounts ⋈ content_analyses(분석 완료) ⋈ LATERAL 최신 스냅샷).
 * 분석 층 소유 테이블과의 조인 결과라 생산자와 공유 형태가 성립하지 않아 was 로컬 record다(§4-4).
 */
public record ContentListRow(
		String shortCode, String thumbnailUrl, String caption, OffsetDateTime postedAt, String contentType,
		String handle, String displayName, String profileImageUrl, Long followers,
		Long views, Long likes, Long comments, Long hypeScore, OffsetDateTime capturedAt,
		String adType, String productCategoriesJson, Integer brandCount) {
}
