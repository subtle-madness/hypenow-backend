package com.celfit.analytics.llm;

import java.util.List;

/**
 * 댓글 6분류 포트 — 유일한 LLM 경계. 테스트는 이 포트를 fake로 대체한다
 * (실 API 호출 금지 — ARCHITECTURE §4-7). 구현: AnthropicCommentClassifier.
 */
public interface CommentClassificationPort {

	List<ClassifiedComment> classify(List<CommentToClassify> comments);
}
