package com.celfit.was.v2.influencer;

import java.math.BigDecimal;
import java.util.List;

/**
 * 스펙 6.22 발굴 리포트 AI 분석 v2 — 프론트는 6.4를 페어 호출해 차트 막대·광고 스트립·게시물 카드를
 * 파생하므로 이 응답엔 게시물별 재료(bars)가 없다. tagline·perfSummary·contentSummary는 비-null
 * 계약 — 카피 미생성 계정은 컨트롤러가 404(리포트 미생성). sponsored·adsSummary는 광고 0건이면 null.
 */
public record InfluencerAiReportV2(String tagline, Long analyzedCount, Long totalPosts,
		Long effectiveFollowers, Activity activity,
		String perfSummary, String contentSummary, String adsSummary,
		StatSet overall, StatSet sponsored,
		List<TrendPoint> viewsTrend, List<TrendPoint> erTrend,
		ContentMix contentMix, Ads ads) {

	public record Activity(Long lastUploadDaysAgo, BigDecimal avgIntervalDays) {
	}

	/** overall·sponsored 공용 지표 세트. views.value는 조회수 공개 게시물 평균 — 세트 내 해당 게시물 없으면 null. */
	public record StatSet(MetricCell views, MetricCell er, MetricCell likes, MetricCell comments,
			BigDecimal viewsPerFollower, Long sampleCount) {
	}

	/** growthPct: 표본 올린 순 반분, 앞 구간 평균 대비 뒤 구간 평균 증감률(정수 %). 반분 불가(표본 2 미만)면 null. */
	public record MetricCell(BigDecimal value, Integer growthPct) {
	}

	/** date는 KST 달력 날짜 "YYYY-MM-DD"(스펙 3.4). */
	public record TrendPoint(String date, BigDecimal value) {
	}

	public record ContentMix(List<Category> categories, List<String> traits) {
		public record Category(String label, Long count) {
		}
	}

	/** 광고 0건이어도 항상 포함(sponsoredCount 0, 나머지 null·빈 배열). */
	public record Ads(Long sponsoredCount, Long adIntervalDays, Long lastAdDaysAgo, String headline,
			List<Brand> brands) {
		/** contentIds는 올린 순 short_code — 프론트가 6.4 recentContents와 조인. */
		public record Brand(String name, Long count, List<OtherInfluencer> otherInfluencers,
				List<String> contentIds) {
			/** id는 handle 그대로(6.4 확정 준용). */
			public record OtherInfluencer(String id, String handle) {
			}
		}
	}
}
