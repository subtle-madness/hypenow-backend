package com.celfit.contract.analysis;

import java.time.OffsetDateTime;

/**
 * 윈도우 내 게시물 시계열 1행 (미러: analytics.v_account_content_series → account_content_series).
 * 차트 막대·광고 스트립·최근 콘텐츠 탭 재료. views NULL = 피드(미공개) — 표현 규약은 was가 정한다.
 */
public record AccountContentPoint(String shortCode, String accountHandle, OffsetDateTime postedAt,
		String contentType, Long views, Long likes, Long comments, Boolean sponsored) {
}
