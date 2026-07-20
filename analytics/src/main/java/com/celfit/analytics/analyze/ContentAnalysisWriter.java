package com.celfit.analytics.analyze;

import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.Synthesis;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** content_analyses INSERT 단일 원천 — 일상 잡과 백필 러너가 공유한다. 컬럼 변경 시 이 한 곳만. */
final class ContentAnalysisWriter {

	private ContentAnalysisWriter() {}

	/**
	 * @param conflictIgnore true면 이미 분석된 행은 건너뛴다(백필 재실행 멱등 — 일상 잡은 false).
	 * @param metricTimeliness 지표 시점 마킹(V33) — 일상 잡(ContentAnalysisJob)·백필 러너
	 *        (GeminiBackfillRunner) 모두 제때 가드 충족 여부(timely)에 따라 timely/late_backfill을
	 *        직접 분기한다(07-20 개정: 늦크롤도 최근 N개 윈도우 안이면 late_backfill로 대상에 포함 —
	 *        더 이상 "백필=항상 late_backfill"이 아니다). 어휘는 V33 CHECK가 단일 원천.
	 */
	static void insert(JdbcTemplate analysis, ObjectMapper json, String shortCode, String model,
			Baseline b, ContentAttributes attrs, Synthesis s, boolean conflictIgnore,
			String metricTimeliness) {
		analysis.update("""
				INSERT INTO content_analyses (short_code, model,
				  ai_content_summary, contents_pattern, ai_comment_insight,
				  recent_reels_avg_views, rank_in_recent_reels, recent_reels_count, recent_contents_count,
				  recent12_avg_engagement_rate, recent12_avg_like_count, recent12_avg_comment_count,
				  category_top_percentile, category_avg_views, category_sample_size,
				  detected_brands, sponsored_signal_level, sponsored_signal_reasons, ad_disclosure,
				  detected_product_categories, detected_products, vlm_attributes, main_category, sub_categories,
				  detected_distributors, ad_type,
				  comment_authenticity_grade, comment_authenticity_note, metric_timeliness)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
				        ?::jsonb, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)"""
				+ (conflictIgnore ? " ON CONFLICT (short_code) DO NOTHING" : ""),
				shortCode, model,
				s.aiContentSummary(), s.contentsPattern(), s.aiCommentInsight(),
				b.recentReelsAvgViews(), b.rankInRecentReels(), b.recentReelsCount(), b.recentContentsCount(),
				b.recent12AvgEngagementRate(), b.recent12AvgLikeCount(), b.recent12AvgCommentCount(),
				b.categoryTopPercentile(), b.categoryAvgViews(), b.categorySampleSize(),
				toJson(json, attrs == null ? null : attrs.detectedBrands()),
				attrs == null ? null : attrs.sponsoredSignalLevel(),
				toJson(json, attrs == null ? null : attrs.sponsoredSignalReasons()),
				attrs == null ? null : attrs.adDisclosure(),
				toJson(json, attrs == null ? null : attrs.detectedProductCategories()),
				toJson(json, attrs == null ? null : attrs.detectedProducts()),
				toJson(json, attrs == null ? null : attrs.vlmAttributes()),
				attrs == null ? null : attrs.mainCategory(),
				toJson(json, attrs == null ? null : attrs.subCategories()),
				toJson(json, attrs == null ? null : attrs.detectedDistributors()),
				attrs == null ? null : attrs.adType(),
				s.commentAuthenticityGrade(), s.commentAuthenticityNote(), metricTimeliness);
	}

	private static String toJson(ObjectMapper json, Object value) {
		return value == null ? null : json.writeValueAsString(value);
	}
}
