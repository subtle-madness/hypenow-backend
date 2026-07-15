package com.celfit.was.v1.influencer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 6.5 인플루언서 AI 리포트 조회 — analysis DB 미러 읽기 전용(분석 결과끼리 조인은 허용). */
@Repository
public class V1InfluencerReportRepository {

	private final JdbcClient jdbcClient;

	public V1InfluencerReportRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** account_summaries 1행 — 없으면 empty → 404 (스펙 6.5 에러 규약). */
	public Optional<SummaryRow> findSummary(String handle) {
		return jdbcClient.sql("""
				SELECT analyzed_count, posts_count, metric, avg_views, views_per_follower,
				       avg_er_pct, avg_likes, avg_comments, trend_direction, sponsored_count,
				       organic_avg, ad_avg, ad_drop_pct, comparison_organic_count, comparison_ad_count,
				       last_ad_posted_at, last_posted_at, avg_interval_days
				FROM account_summaries
				WHERE handle = :h
				""").param("h", handle).query(SummaryRow.class).optional();
	}

	/** 계정 LLM 카피 — 이력 테이블(account_analyses)에서 계정별 최신 1행만 유효. 없으면 카피 전부 null. */
	public Optional<CopyRow> findLatestCopy(String handle) {
		return jdbcClient.sql("""
				SELECT tagline, summary, trend_note, chart_note, traits::text AS traits_json,
				       ad_headline, pace_note
				FROM account_analyses
				WHERE handle = :h
				ORDER BY analyzed_at DESC
				LIMIT 1
				""").param("h", handle).query(CopyRow.class).optional();
	}

	/** 윈도우 내 게시물 시계열 — 올린 순(posted_at ASC, 2차 short_code). chart.bars·ads.strip 재료. */
	public List<SeriesRow> findSeries(String handle) {
		return jdbcClient.sql("""
				SELECT posted_at, content_type, views, likes, comments, sponsored
				FROM account_content_series
				WHERE account_handle = :h
				ORDER BY posted_at, short_code
				""").param("h", handle).query(SeriesRow.class).list();
	}

	/** contentMix.categories — 시계열 윈도우 콘텐츠 × 분석 대분류, label은 main_label 폴백 main_category. */
	public List<CategoryRow> findCategories(String handle) {
		return jdbcClient.sql("""
				SELECT COALESCE(t.main_label, an.main_category) AS label, count(*) AS cnt
				FROM account_content_series s
				JOIN content_analyses an ON an.short_code = s.short_code
				LEFT JOIN (SELECT DISTINCT main_value, main_label FROM beauty_taxonomy) t
				  ON t.main_value = an.main_category
				WHERE s.account_handle = :h AND an.main_category IS NOT NULL
				GROUP BY 1 ORDER BY cnt DESC, label
				""").param("h", handle).query(CategoryRow.class).list();
	}

	/** ads.brands — 광고 콘텐츠(ad_type='sponsored')의 detected_brands name 집계. */
	public List<BrandRow> findBrands(String handle) {
		return jdbcClient.sql("""
				SELECT b->>'name' AS name, count(*) AS cnt
				FROM account_content_series s
				JOIN content_analyses an ON an.short_code = s.short_code
				CROSS JOIN LATERAL jsonb_array_elements(COALESCE(an.detected_brands, '[]'::jsonb)) b
				WHERE s.account_handle = :h AND an.ad_type = 'sponsored'
				GROUP BY 1 ORDER BY cnt DESC, name
				""").param("h", handle).query(BrandRow.class).list();
	}

	public record SummaryRow(Long analyzedCount, Long postsCount, String metric, Long avgViews,
			BigDecimal viewsPerFollower, BigDecimal avgErPct, Long avgLikes, Long avgComments,
			String trendDirection, Long sponsoredCount, Long organicAvg, Long adAvg, Integer adDropPct,
			Long comparisonOrganicCount, Long comparisonAdCount, OffsetDateTime lastAdPostedAt,
			OffsetDateTime lastPostedAt, BigDecimal avgIntervalDays) {
	}

	public record CopyRow(String tagline, String summary, String trendNote, String chartNote,
			String traitsJson, String adHeadline, String paceNote) {
	}

	public record SeriesRow(OffsetDateTime postedAt, String contentType,
			Long views, Long likes, Long comments, Boolean sponsored) {
	}

	public record CategoryRow(String label, Long cnt) {
	}

	public record BrandRow(String name, Long cnt) {
	}
}
