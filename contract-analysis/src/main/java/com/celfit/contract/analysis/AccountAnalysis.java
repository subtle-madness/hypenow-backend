package com.celfit.contract.analysis;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 계정 LLM 카피 1행 (analytics 잡이 조립·INSERT, was/E가 계정별 최신 1행(analyzed_at DESC 첫 행) SELECT — analysis DB account_analyses).
 * 이력 테이블: 행은 INSERT로만 쌓인다. inputLastPostedAt = 분석 당시 미러의 last_posted_at(stale 판정 기준).
 * adHeadline: 광고 비교 데이터(organic_avg·ad_avg 모두 존재)가 있을 때만 값, 아니면 null (프론트 계약).
 * traits: 성향 태그 3~5개 — DB엔 jsonb 배열, 직렬화는 생산자/소비자 각자의 매핑 계층에서.
 */
public record AccountAnalysis(String handle, OffsetDateTime analyzedAt, String model,
		OffsetDateTime inputLastPostedAt, Long inputAnalyzedCount, String tagline, String summary,
		String trendNote, String chartNote, List<String> traits, String adHeadline, String paceNote) {
}
