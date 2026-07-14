package com.celfit.was.postdetail;

/**
 * 댓글 + AI 분류 LEFT JOIN 1행 (content_comments ⋈ comment_classifications — 둘 다 분석 결과라 조인 허용).
 * 분석 층 소유 테이블은 생산자와 공유 형태가 성립하지 않아 was 로컬 record다(§4-4). 미분류면 aiCategory null.
 */
public record CommentRow(Long id, String authorMasked, String body, Long likeCount, String aiCategory) {
}
