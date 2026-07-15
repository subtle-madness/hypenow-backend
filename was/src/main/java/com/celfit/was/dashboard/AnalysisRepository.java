package com.celfit.was.dashboard;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisRepository {

	private static final Logger log = LoggerFactory.getLogger(AnalysisRepository.class);

	private final JdbcTemplate jdbcTemplate;

	public AnalysisRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<Map<String, Object>> contentRanking() {
		return safeQueryForList(
				"SELECT rank_overall, short_code, owner_username, main_group, engagement_rate, likes, views "
						+ "FROM content_ranking ORDER BY rank_overall NULLS LAST",
				"content_ranking");
	}

	public List<Map<String, Object>> categoryPerformance() {
		return safeQueryForList(
				"SELECT main_group, content_count, avg_engagement_rate, median_engagement_rate, avg_likes "
						+ "FROM category_performance "
						+ "WHERE subcategory='(all)' AND keyword='(all)' AND main_group IS NOT NULL "
						+ "ORDER BY avg_engagement_rate DESC",
				"category_performance");
	}

	public List<Map<String, Object>> creatorPerformance() {
		return safeQueryForList(
				"SELECT owner_username, content_count, avg_engagement_rate, followers "
						+ "FROM creator_performance ORDER BY avg_engagement_rate DESC",
				"creator_performance");
	}

	public List<Map<String, Object>> overperformers() {
		return safeQueryForList(
				"SELECT owner_username, tier, avg_engagement_rate, tier_median_er "
						+ "FROM creator_overperformance WHERE overperforms ORDER BY avg_engagement_rate DESC",
				"creator_overperformance");
	}

	public List<Map<String, Object>> tierDistribution() {
		return safeQueryForList(
				"SELECT tier, creator_count, content_count, avg_engagement_rate "
						+ "FROM tier_distribution ORDER BY tier",
				"tier_distribution");
	}

	public List<Map<String, Object>> contentType() {
		return safeQueryForList(
				"SELECT content_format, content_count, avg_engagement_rate, avg_likes, avg_views "
						+ "FROM content_type_performance ORDER BY avg_engagement_rate DESC",
				"content_type_performance");
	}

	public List<Map<String, Object>> hashtags() {
		return safeQueryForList(
				"SELECT tag, content_count, avg_engagement_rate FROM hashtag_performance "
						+ "ORDER BY content_count DESC, avg_engagement_rate DESC LIMIT 15",
				"hashtag_performance");
	}

	public Map<String, Object> freshness() {
		try {
			return jdbcTemplate.queryForMap(
					"SELECT max(materialized_at) AS at, count(*) AS tables, sum(row_count) AS rows "
							+ "FROM materialization_meta");
		} catch (DataAccessException e) {
			log.warn("materialization_meta 조회 실패, 빈 값으로 대체합니다: {}", e.getMessage());
			return Collections.emptyMap();
		}
	}

	private List<Map<String, Object>> safeQueryForList(String sql, String tableName) {
		try {
			return jdbcTemplate.queryForList(sql);
		} catch (DataAccessException e) {
			log.warn("{} 조회 실패, 빈 목록으로 대체합니다: {}", tableName, e.getMessage());
			return Collections.emptyList();
		}
	}
}
