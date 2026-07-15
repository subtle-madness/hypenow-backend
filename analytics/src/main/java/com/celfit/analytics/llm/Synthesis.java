package com.celfit.analytics.llm;

/** LLM 종합 산출 — 텍스트 3종 + 댓글 진정성 판정 (스펙 §3 content_analyses). */
public record Synthesis(String aiContentSummary, String contentsPattern, String aiCommentInsight,
		String commentAuthenticityGrade, String commentAuthenticityNote) {
}
