package com.celfit.was.v1.content;

import java.math.BigDecimal;
import java.util.List;

/** 스펙 6.3 AI 분석 리포트 응답 — 구조는 프론트 스펙 그대로 유지한다. */
public record ContentAiReport(Scope scope, String summary, Comparison comparison,
		CategoryContext categoryContext, VlmAnalysis vlmAnalysis,
		CommentAnalysis commentAnalysis, List<Comment> comments) {

	public record Scope(String basis, long analyzedCount) {
	}

	public record Comparison(Views views, EngagementRate engagementRate,
			EngagementQuality engagementQuality, String narrative) {
		public record Views(Long value, Long baseline, BigDecimal multiple,
				Integer rankInRecent, Integer recentCount, List<ReelPoint> recentReels) {
			public record ReelPoint(String contentId, Long views, String postedAt, boolean isCurrent) {
			}
		}
		public record EngagementRate(BigDecimal value, BigDecimal baseline) {
		}
		public record EngagementQuality(Counts likes, Counts comments) {
			public record Counts(Long count, Long baselineCount) {
			}
		}
	}

	public record CategoryContext(String categoryLabel, Integer percentile,
			Long categoryAvgViews, Long sampleSize) {
	}

	public record VlmAnalysis(List<Brand> brands, SponsoredSignal sponsoredSignal,
			String adDisclosure, List<String> productCategories, List<Attribute> attributes) {
		public record Brand(String name, String evidence) {
		}
		public record SponsoredSignal(String level, List<String> reasons) {
		}
		public record Attribute(String label, String value) {
		}
	}

	public record CommentAnalysis(List<Slice> distribution, Signals signals, String insight) {
		public record Slice(String category, BigDecimal ratio) {
		}
		public record Signals(BigDecimal adAversionRate, BigDecimal friendTagRate, Authenticity authenticity) {
			public record Authenticity(String grade, String note) {
			}
		}
	}

	public record Comment(String id, String author, String text, Long likes, String category) {
	}
}
