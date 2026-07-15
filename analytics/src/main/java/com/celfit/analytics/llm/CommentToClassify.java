package com.celfit.analytics.llm;

/** 분류 입력 — 댓글 1건. id는 raw 댓글 id. */
public record CommentToClassify(long id, String text) {
}
