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
				SELECT followers, analyzed_count, posts_count, metric, avg_views, views_per_follower,
				       avg_er_pct, avg_likes, avg_comments, last_posted_at, avg_interval_days
				FROM account_summaries
				WHERE handle = :h
				""").param("h", handle).query(SummaryRow.class).optional();
	}

	/** 계정 LLM 카피 — 이력 테이블(account_analyses)에서 계정별 최신 1행만 유효. 없으면 카피 전부 null. */
	public Optional<CopyRow> findLatestCopy(String handle) {
		return jdbcClient.sql("""
				SELECT tagline, traits::text AS traits_json, perf_summary, content_summary, ad_summary
				FROM account_analyses
				WHERE handle = :h
				ORDER BY analyzed_at DESC
				LIMIT 1
				""").param("h", handle).query(CopyRow.class).optional();
	}

	/** 윈도우 내 게시물 시계열 — 올린 순(posted_at ASC, 2차 short_code). chart.bars·ads.strip·미리보기 재료.
	 *  sponsored는 캡션 분류(content_analyses.ad_type='sponsored')가 정본 — series 자체의 sponsored
	 *  (인스타 유료파트너십 태그 ad_marked)는 캡션 고지 광고를 놓쳐 brands와 어긋나므로 안 쓴다.
	 *  분석 결과끼리 조인(§4-4 허용). 분석행 없으면 false. */
	public List<SeriesRow> findSeries(String handle) {
		return jdbcClient.sql("""
				SELECT s.posted_at, s.content_type, s.views, s.likes, s.comments,
				       COALESCE(an.ad_type = 'sponsored', false) AS sponsored,
				       c.caption,
				       COALESCE('/img/' || it.object_path, c.thumbnail_url) AS thumbnail_url,
				       -- 미리보기 bar 1개당 브랜드 배열 첫 항목만 표시(다중 브랜드 협업이면 대표성 보장 안 됨).
				       -- ads.headline의 topBrand(findBrands 빈도 최다 집계)와는 선택 기준이 다르다.
				       (SELECT b->>'name' FROM jsonb_array_elements(COALESCE(an.detected_brands, '[]'::jsonb)) b
				        LIMIT 1) AS brand
				FROM account_content_series s
				LEFT JOIN content_analyses an ON an.short_code = s.short_code
				LEFT JOIN contents c ON c.short_code = s.short_code
				LEFT JOIN image_assets it ON it.kind = 'thumbnail' AND it.key = s.short_code
				WHERE s.account_handle = :h
				ORDER BY s.posted_at, s.short_code
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

	/** ads.products — 광고 콘텐츠(ad_type='sponsored')의 detected_products name 집계 (V30). */
	public List<ProductRow> findProducts(String handle) {
		return jdbcClient.sql("""
				SELECT p->>'name' AS name, count(*) AS cnt
				FROM account_content_series s
				JOIN content_analyses an ON an.short_code = s.short_code
				CROSS JOIN LATERAL jsonb_array_elements(COALESCE(an.detected_products, '[]'::jsonb)) p
				WHERE s.account_handle = :h AND an.ad_type = 'sponsored'
				GROUP BY 1 ORDER BY cnt DESC, name
				""").param("h", handle).query(ProductRow.class).list();
	}

	/** 피어 퍼센타일·중앙값 ER (account_peer_stats, V39) — 미러 아닌 파생 뷰 직접 읽기. */
	public Optional<PeerStatsRow> findPeerStats(String handle) {
		return jdbcClient.sql("""
				SELECT peer_size, top_pct_views, top_pct_er, top_pct_likes, top_pct_comments,
				       top_pct_ad_views, top_pct_ad_er, top_pct_ad_likes, top_pct_ad_comments,
				       peer_median_er_pct, global_median_er_pct
				FROM account_peer_stats
				WHERE handle = :h
				""").param("h", handle).query(PeerStatsRow.class).optional();
	}

	/** 유사 인플루언서 — 같은 주 카테고리(account_peer_stats) 후보를 traits 교집합 내림차순,
	 *  팔로워 근접 오름차순으로 상위 6. 카피 없는 계정은 후보 제외(LATERAL INNER).
	 *  overlap·union 모두 중복 제거(DISTINCT) — matchPct ≤ 100 보장(traits는 LLM 산출이라
	 *  유일성 보장 없음 — count(*)면 중복 원소 있는 후보의 overlap>union으로 100% 초과 가능했음).
	 *  기준 계정이 없거나(account_peer_stats·account_analyses 미존재) 같은 카테고리 후보가
	 *  없으면 빈 목록을 돌려준다 — findSummary(Optional)와 달리 존재 확인용이 아니라 목록
	 *  조회라 404로 이어지지 않고, 컨트롤러도 200+[]로 그대로 응답한다. */
	public List<SimilarRow> findSimilar(String handle) {
		return jdbcClient.sql("""
				WITH me AS (
				  SELECT p.peer_category, ac.followers, la.traits
				  FROM account_peer_stats p
				  JOIN accounts ac ON ac.handle = p.handle
				  JOIN LATERAL (SELECT traits FROM account_analyses
				                WHERE handle = p.handle ORDER BY analyzed_at DESC LIMIT 1) la ON true
				  WHERE p.handle = :h
				)
				SELECT c.handle AS influencer_id, ac.display_name AS name,
				       COALESCE('/img/' || ip.object_path, ac.profile_image_url) AS profile_image_url,
				       la.tagline,
				       (SELECT count(DISTINCT t.value) FROM jsonb_array_elements_text(la.traits) t
				         WHERE t.value IN (SELECT value FROM jsonb_array_elements_text(me.traits))) AS overlap_n,
				       (SELECT count(DISTINCT value) FROM (
				          SELECT value FROM jsonb_array_elements_text(la.traits)
				          UNION ALL SELECT value FROM jsonb_array_elements_text(me.traits)) u) AS union_n
				FROM account_peer_stats c
				JOIN me ON c.peer_category = me.peer_category
				JOIN accounts ac ON ac.handle = c.handle
				JOIN LATERAL (SELECT tagline, traits FROM account_analyses
				              WHERE handle = c.handle ORDER BY analyzed_at DESC LIMIT 1) la ON true
				LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = c.handle
				WHERE c.handle <> :h
				ORDER BY overlap_n DESC, abs(ac.followers - me.followers) ASC
				LIMIT 6
				""").param("h", handle).query(SimilarRow.class).list();
	}

	/** overlap·union 모두 중복 제거(DISTINCT) — matchPct ≤ 100 보장. */
	public record SimilarRow(String influencerId, String name, String profileImageUrl,
			String tagline, Long overlapN, Long unionN) {
	}

	public record SummaryRow(Long followers, Long analyzedCount, Long postsCount, String metric,
			Long avgViews, BigDecimal viewsPerFollower, BigDecimal avgErPct, Long avgLikes,
			Long avgComments, OffsetDateTime lastPostedAt, BigDecimal avgIntervalDays) {
	}

	public record CopyRow(String tagline, String traitsJson, String perfSummary,
			String contentSummary, String adSummary) {
	}

	public record SeriesRow(OffsetDateTime postedAt, String contentType, Long views, Long likes,
			Long comments, Boolean sponsored, String caption, String thumbnailUrl, String brand) {
	}

	public record CategoryRow(String label, Long cnt) {
	}

	public record BrandRow(String name, Long cnt) {
	}

	public record ProductRow(String name, Long cnt) {
	}

	public record PeerStatsRow(Long peerSize, Integer topPctViews, Integer topPctEr,
			Integer topPctLikes, Integer topPctComments, Integer topPctAdViews, Integer topPctAdEr,
			Integer topPctAdLikes, Integer topPctAdComments, BigDecimal peerMedianErPct,
			BigDecimal globalMedianErPct) {
	}
}
