package com.celfit.was.postdetail;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 서빙 미러 3종 + 분석 층 소유 테이블 2종 조회. contents·accounts는 계약 record로 매핑하고(§4-3),
 * 댓글은 분류 조인 결과라서, comment_classifications·content_analyses는 공유 형태가 성립하지
 * 않아서 was 로컬 record로 매핑한다(§4-4). 어느 쪽이든 부재 시 빈 값으로 저하한다(대시보드 컨벤션).
 */
@Repository
public class PostDetailRepository {

	private static final Logger log = LoggerFactory.getLogger(PostDetailRepository.class);

	private final JdbcClient jdbcClient;

	public PostDetailRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Optional<Content> findContent(String shortCode) {
		return safeQuery("contents", Optional::empty, () -> jdbcClient.sql("""
				SELECT short_code, account_handle, thumbnail_url, caption, posted_at,
				       content_type, video_duration, original_url,
				       views, likes, comments, hype_score
				FROM contents
				WHERE short_code = :shortCode
				""")
				.param("shortCode", shortCode)
				.query(Content.class)
				.optional());
	}

	public Optional<Account> findAccount(String handle) {
		return safeQuery("accounts", Optional::empty, () -> jdbcClient.sql("""
				SELECT handle, display_name, profile_image_url, followers
				FROM accounts
				WHERE handle = :handle
				""")
				.param("handle", handle)
				.query(Account.class)
				.optional());
	}

	// comment_classifications 부재 시 LEFT JOIN 전체가 실패해 댓글까지 빈 목록으로 저하된다 —
	// B2 이후 두 테이블이 같은 Flyway로 함께 배포되므로 수용한 트레이드오프(계획 문서에 기록됨).
	public List<CommentRow> findComments(String shortCode) {
		return safeQuery("content_comments(+classifications 조인)", List::of, () -> jdbcClient.sql("""
				SELECT m.id, m.author_masked, m.body, m.like_count, k.ai_category
				FROM content_comments m
				LEFT JOIN comment_classifications k ON k.id = m.id
				WHERE m.short_code = :shortCode
				ORDER BY m.like_count DESC NULLS LAST, m.id
				""")
				.param("shortCode", shortCode)
				.query(CommentRow.class)
				.list());
	}

	/** content_analyses 1행 — 분석 전이면 empty (응답의 analysis 블록이 null이 된다). */
	public Optional<ContentAnalysisRow> findAnalysis(String shortCode) {
		return safeQuery("content_analyses", Optional::empty, () -> jdbcClient.sql("""
				SELECT analyzed_at, ai_content_summary, contents_pattern, ai_comment_insight,
				       recent_reels_avg_views, rank_in_recent_reels, recent_reels_count,
				       recent_contents_count, recent12_avg_engagement_rate, recent12_avg_like_count,
				       recent12_avg_comment_count, category_top_percentile, category_avg_views,
				       category_sample_size,
				       detected_brands::text              AS detected_brands_json,
				       sponsored_signal_level,
				       sponsored_signal_reasons::text     AS sponsored_signal_reasons_json,
				       ad_disclosure,
				       detected_product_categories::text  AS detected_product_categories_json,
				       vlm_attributes::text               AS vlm_attributes_json,
				       main_category,
				       sub_categories::text               AS sub_categories_json,
				       ad_type, comment_authenticity_grade, comment_authenticity_note
				FROM content_analyses
				WHERE short_code = :shortCode
				""")
				.param("shortCode", shortCode)
				.query(ContentAnalysisRow.class)
				.optional());
	}

	private <T> T safeQuery(String table, Supplier<T> fallback, Supplier<T> query) {
		try {
			return query.get();
		} catch (DataAccessException e) {
			log.warn("{} 조회 실패, 빈 값으로 대체합니다: {}", table, e.getMessage());
			return fallback.get();
		}
	}
}
