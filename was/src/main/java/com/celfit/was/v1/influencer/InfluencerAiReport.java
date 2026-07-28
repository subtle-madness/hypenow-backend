package com.celfit.was.v1.influencer;

import java.math.BigDecimal;
import java.util.List;

/** 스펙 6.5 인플루언서 AI 리포트 응답 — 카피 없음(account_analyses 미생성)이어도 블록 구조는 유지. */
public record InfluencerAiReport(String tagline, Long analyzedCount, Long totalPosts, String summary,
		Stats stats, Trend trend, Chart chart, ContentMix contentMix, Ads ads, Activity activity) {

	public record Stats(String metric, Long avgViews, BigDecimal viewsPerFollower,
			BigDecimal avgEr, Long avgLikes, Long avgComments) {
	}

	public record Trend(String direction, String note) {
	}

	public record Chart(String metric, String note, List<Bar> bars) {
		public record Bar(Long views, Long likes, Long comments, String postedAt,
				Boolean sponsored, String contentType) {
		}
	}

	public record ContentMix(List<Category> categories, List<String> traits) {
		public record Category(String label, Long count) {
		}
	}

	public record Ads(Long sponsoredCount, List<Boolean> strip, String lastAdNote,
			Comparison comparison, String headline, List<Brand> brands) {
		public record Comparison(String metric, Long organicCount, Long organicAvg,
				Long adCount, Long adAvg, Integer dropPct) {
		}
		public record Brand(String name, Long count) {
		}
	}

	public record Activity(Long lastUploadDaysAgo, Boolean isActive,
			BigDecimal avgIntervalDays, String paceNote) {
	}
}
