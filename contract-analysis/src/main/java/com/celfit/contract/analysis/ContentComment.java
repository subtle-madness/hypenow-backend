package com.celfit.contract.analysis;

/** 서빙 댓글 1행 (미러: analytics.v_content_comments → content_comments). 작성자는 마스킹된 값만. */
public record ContentComment(Long id, String shortCode, String authorMasked, String body, Long likeCount) {
}
