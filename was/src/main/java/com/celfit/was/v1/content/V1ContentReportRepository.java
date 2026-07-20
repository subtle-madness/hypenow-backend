package com.celfit.was.v1.content;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 6.3 AI 리포트 조회 — analysis DB 미러 읽기 전용. */
@Repository
public class V1ContentReportRepository {

	private final JdbcClient jdbcClient;

	public V1ContentReportRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 콘텐츠 + 분석 1행. 분석 미생성이면 empty → 404 (스펙 6.3 에러 규약). */
	public Optional<ReportRow> findReport(String shortCode) {
		return jdbcClient.sql("""
				SELECT c.short_code, c.account_handle, c.content_type, c.views, c.likes, c.comments,
				       an.ai_content_summary, an.contents_pattern, an.ai_comment_insight,
				       an.recent_reels_avg_views, an.rank_in_recent_reels, an.recent_reels_count,
				       an.recent_contents_count, an.recent12_avg_engagement_rate,
				       an.recent12_avg_like_count, an.recent12_avg_comment_count,
				       an.category_top_percentile, an.category_avg_views, an.category_sample_size,
				       an.main_category,
				       an.detected_brands::text AS brands_json,
				       an.sponsored_signal_level, an.sponsored_signal_reasons::text AS reasons_json,
				       an.ad_disclosure, an.detected_product_categories::text AS product_categories_json,
				       an.vlm_attributes::text AS attributes_json,
				       an.comment_authenticity_grade, an.comment_authenticity_note,
				       (SELECT t.main_label FROM beauty_taxonomy t
				        WHERE t.main_value = an.main_category LIMIT 1) AS category_label
				FROM contents c
				JOIN content_analyses an ON an.short_code = c.short_code
				WHERE c.short_code = :sc
				""").param("sc", shortCode).query(ReportRow.class).optional();
	}

	/** 그 계정 릴스 시계열 (윈도우 내, 올린 순) — comparison.views.recentReels 재료 (라이브 재계산 기준). */
	public List<ReelPointRow> findRecentReels(String handle) {
		return jdbcClient.sql("""
				SELECT short_code, views, posted_at FROM account_content_series
				WHERE account_handle = :h AND content_type = 'reels'
				ORDER BY posted_at
				""").param("h", handle).query(ReelPointRow.class).list();
	}

	/** 댓글 AI 분류 집계. */
	public Map<String, Long> countByCategory(String shortCode) {
		return jdbcClient.sql("""
				SELECT ai_category, count(*) AS cnt FROM comment_classifications
				WHERE short_code = :sc GROUP BY ai_category
				""").param("sc", shortCode)
				.query((rs, i) -> Map.entry(rs.getString("ai_category"), rs.getLong("cnt")))
				.list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/** 댓글 원문 + 분류 (무분류는 etc 폴백). */
	public List<CommentRow> findComments(String shortCode) {
		return jdbcClient.sql("""
				SELECT cm.id, cm.author_masked, cm.body, cm.like_count,
				       COALESCE(cc.ai_category, 'etc') AS category
				FROM content_comments cm
				LEFT JOIN comment_classifications cc ON cc.id = cm.id
				WHERE cm.short_code = :sc
				ORDER BY cm.like_count DESC NULLS LAST, cm.id
				""").param("sc", shortCode).query(CommentRow.class).list();
	}

	public record ReportRow(String shortCode, String accountHandle, String contentType,
			Long views, Long likes, Long comments,
			String aiContentSummary, String contentsPattern, String aiCommentInsight,
			// 이 3컬럼(recentReelsAvgViews/rankInRecentReels/recentReelsCount)은 assembler가 소비하지
			// 않지만 향후/디버그용으로 의도적으로 유지 — 차트는 라이브 재계산으로 전환됨(A2).
			Long recentReelsAvgViews, Integer rankInRecentReels, Integer recentReelsCount,
			Integer recentContentsCount, BigDecimal recent12AvgEngagementRate,
			Long recent12AvgLikeCount, Long recent12AvgCommentCount,
			Integer categoryTopPercentile, Long categoryAvgViews, Long categorySampleSize,
			String mainCategory, String brandsJson, String sponsoredSignalLevel, String reasonsJson,
			String adDisclosure, String productCategoriesJson, String attributesJson,
			String commentAuthenticityGrade, String commentAuthenticityNote, String categoryLabel) {
	}

	public record ReelPointRow(String shortCode, Long views, OffsetDateTime postedAt) {
	}

	public record CommentRow(Long id, String authorMasked, String body, Long likeCount, String category) {
	}
}
