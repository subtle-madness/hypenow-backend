package com.celfit.was.postdemo;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 게시물 상세 드로어 데모용 조회 — contents ⟕ accounts ⟕ content_analyses + 댓글 분석. */
@Repository
public class PostDemoRepository {

	private final JdbcClient jdbcClient;

	public PostDemoRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Optional<Map<String, Object>> find(String shortCode) {
		return jdbcClient.sql("""
				SELECT c.short_code, c.account_handle, c.caption, c.posted_at, c.content_type,
				       c.views, c.likes, c.comments, c.hype_score, c.original_url,
				       a.display_name, a.followers,
				       ca.analyzed_at, ca.model,
				       ca.ai_content_summary, ca.contents_pattern, ca.ai_comment_insight,
				       ca.recent_reels_avg_views, ca.rank_in_recent_reels, ca.recent_reels_count,
				       ca.recent_contents_count, ca.recent12_avg_engagement_rate,
				       ca.recent12_avg_like_count, ca.recent12_avg_comment_count,
				       ca.category_top_percentile, ca.category_avg_views, ca.category_sample_size,
				       ca.detected_brands::text AS detected_brands,
				       ca.sponsored_signal_level,
				       ca.sponsored_signal_reasons::text AS sponsored_signal_reasons,
				       ca.ad_disclosure,
				       ca.detected_product_categories::text AS detected_product_categories,
				       ca.vlm_attributes::text AS vlm_attributes,
				       ca.main_category,
				       ca.sub_categories::text AS sub_categories,
				       ca.ad_type, ca.comment_authenticity_grade, ca.comment_authenticity_note
				FROM contents c
				LEFT JOIN accounts a ON a.handle = c.account_handle
				LEFT JOIN content_analyses ca ON ca.short_code = c.short_code
				WHERE c.short_code = :shortCode""")
				.param("shortCode", shortCode)
				.query().listOfRows().stream().findFirst();
	}

	public List<Map<String, Object>> commentBreakdown(String shortCode) {
		return jdbcClient.sql("""
				SELECT ai_category, count(*) AS cnt FROM comment_classifications
				WHERE short_code = :shortCode GROUP BY ai_category ORDER BY cnt DESC""")
				.param("shortCode", shortCode)
				.query().listOfRows();
	}

	public List<Map<String, Object>> topComments(String shortCode, int limit) {
		return jdbcClient.sql("""
				SELECT author_masked, body, like_count FROM content_comments
				WHERE short_code = :shortCode
				ORDER BY like_count DESC NULLS LAST, id LIMIT :limit""")
				.param("shortCode", shortCode)
				.param("limit", limit)
				.query().listOfRows();
	}

	/** 분석이 존재하는 게시물 목록 — /coverage의 상세 데모 진입점. */
	public List<Map<String, Object>> analyzedPosts() {
		return jdbcClient.sql("""
				SELECT ca.short_code, c.account_handle, a.display_name,
				       ca.main_category, ca.ad_type, ca.analyzed_at::date AS analyzed_on,
				       (SELECT count(*) FROM comment_classifications k WHERE k.short_code = ca.short_code) AS classified
				FROM content_analyses ca
				JOIN contents c ON c.short_code = ca.short_code
				LEFT JOIN accounts a ON a.handle = c.account_handle
				ORDER BY ca.analyzed_at DESC""")
				.query().listOfRows();
	}
}
