package com.celfit.contract.analysis;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 계정 LLM 카피 1행 (analytics 잡이 조립·INSERT, was/E가 계정별 최신 1행(analyzed_at DESC 첫 행) SELECT — analysis DB account_analyses).
 * 이력 테이블: 행은 INSERT로만 쌓인다. inputLastPostedAt = 분석 당시 미러의 last_posted_at(stale 판정 기준).
 * adHeadline: 07-27 개편 이후 미기록(was 템플릿 대체)로 전환.
 * perfSummary·contentSummary·adSummary는 신설 섹션별 요약 — AccountAnalysisJob이 신규 행마다 채워 저장한다.
 * V40 이전(07-27 개편 전)에 쌓인 구 이력 행에서만 null이며, 그 자체가 AccountAnalysisJob의 백필 재대상 조건이다.
 * traits: 성향 태그 3~5개 — DB엔 jsonb 배열, 직렬화는 생산자/소비자 각자의 매핑 계층에서.
 * copyVersion: 문구 재생성 게이트(스펙 2026-07-30-perf-summary-statistical-guards-design.md §4) — 기본값 0인
 * 구 이력 행은 CopyRules.VERSION보다 낮아 재생성 대상이 된다.
 */
public record AccountAnalysis(String handle, OffsetDateTime analyzedAt, String model,
		OffsetDateTime inputLastPostedAt, Long inputAnalyzedCount, String tagline, String summary,
		String trendNote, String chartNote, List<String> traits, String adHeadline, String paceNote,
		String perfSummary, String contentSummary, String adSummary, Integer copyVersion) {
}
