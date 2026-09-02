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
				       s.followers,
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
				       an.metric_timeliness,
				       (SELECT t.main_label FROM beauty_taxonomy t
				        WHERE t.main_value = an.main_category LIMIT 1) AS category_label
				FROM contents c
				JOIN content_analyses an ON an.short_code = c.short_code
				LEFT JOIN account_summaries s ON s.handle = c.account_handle
				WHERE c.short_code = :sc
				""").param("sc", shortCode).query(ReportRow.class).optional();
	}

	/**
	 * 같은 대분류 카테고리의 조회수 맥락 — 조회 시점 라이브 집계.
	 *
	 * <p>content_analyses의 category_* 3컬럼은 영구 NULL이다: main_category가 이 행을 만든 통합 1콜
	 * LLM의 산출물이라 저장 시점에는 아직 카테고리를 모르고, 테이블이 불변(INSERT-only)이라 나중에
	 * 채울 수도 없다. 조회수 baseline·rank를 라이브 계산으로 옮긴 A2(07-19)와 같은 처방 —
	 * 과거 분석분 전량에 그대로 소급된다.
	 *
	 * <p>모수 규칙:
	 * <ul>
	 * <li>같은 대분류(main_category) 분석분만 — 대분류가 축을 결정하므로(불변식: main 있음 ⇒ 축
	 *     확정, 서빙 개방 §2) 구 is_beauty=true 게이트는 뷰티 대분류에선 잉여, F&amp;B에선 표본 0건을
	 *     만들어 제거(2026-09-01). 카테고리는 beauty_taxonomy 대분류.
	 * <li><b>조회수 NULL 제외</b> — 피드는 views가 항상 NULL이라(프로젝트 규칙) 모수는 사실상 릴스다.
	 * <li>지표 시점이 timely 또는 레거시 NULL인 건만 — 늦크롤 백필은 조회수가 더 오래 누적돼
	 *     평균을 부풀린다(운영 실측: cleansing 전체 평균 75,543 vs timely 18,499). 랭킹
	 *     /v1/contents가 쓰는 필터와 같은 규칙이다.
	 * </ul>
	 *
	 * @param views 이 게시물의 조회수. NULL이면 순위 비교가 무의미하므로 -1을 넣어 질의하고
	 *              백분위는 조립 단계에서 버린다(평균·표본 수는 그대로 유효).
	 */
	public CategoryContextRow findCategoryContext(String mainCategory, Long views) {
		return jdbcClient.sql("""
				SELECT count(*)                                    AS sample_size,
				       round(avg(c.views))::bigint                 AS avg_views,
				       count(*) FILTER (WHERE c.views > :views)    AS higher_count
				FROM contents c
				JOIN content_analyses an ON an.short_code = c.short_code
				WHERE an.main_category = :mc
				  AND c.views IS NOT NULL
				  AND (an.metric_timeliness = 'timely' OR an.metric_timeliness IS NULL)
				""").param("mc", mainCategory).param("views", views == null ? -1L : views)
				.query(CategoryContextRow.class).single();
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
			Long views, Long likes, Long comments, Long followers,
			String aiContentSummary, String contentsPattern, String aiCommentInsight,
			// 이 3컬럼(recentReelsAvgViews/rankInRecentReels/recentReelsCount)은 assembler가 소비하지
			// 않지만 향후/디버그용으로 의도적으로 유지 — 차트는 라이브 재계산으로 전환됨(A2).
			Long recentReelsAvgViews, Integer rankInRecentReels, Integer recentReelsCount,
			Integer recentContentsCount, BigDecimal recent12AvgEngagementRate,
			Long recent12AvgLikeCount, Long recent12AvgCommentCount,
			// category* 3컬럼도 같은 이유로 남긴 프리즈 값이다 — 영구 NULL이라 조립에 쓰지 않고
			// findCategoryContext 라이브 집계로 대체한다(07-21).
			Integer categoryTopPercentile, Long categoryAvgViews, Long categorySampleSize,
			String mainCategory, String brandsJson, String sponsoredSignalLevel, String reasonsJson,
			String adDisclosure, String productCategoriesJson, String attributesJson,
			String commentAuthenticityGrade, String commentAuthenticityNote, String metricTimeliness,
			String categoryLabel) {
	}

	/** 카테고리 맥락 라이브 집계 1행. higherCount = 이 게시물보다 조회수가 높은 표본 수. */
	public record CategoryContextRow(Long sampleSize, Long avgViews, Long higherCount) {
	}

	public record ReelPointRow(String shortCode, Long views, OffsetDateTime postedAt) {
	}

	public record CommentRow(Long id, String authorMasked, String body, Long likeCount, String category) {
	}
}
