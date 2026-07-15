package com.celfit.was.contentlist;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 랭킹 목록 조회 — contents ⋈ accounts ⋈ content_analyses(분석 완료만) ⋈ LATERAL 최신 스냅샷.
 * 전부 분석 결과끼리의 조인(§4-4 허용). 필터 값은 verbatim 매칭 — 어휘는 생산자 소유.
 * distributor는 저장 컬럼 신설 전까지 지정 시 매칭 0(아래 주석 지점에서 활성화).
 */
@Repository
public class ContentListRepository {

	private static final Logger log = LoggerFactory.getLogger(ContentListRepository.class);
	private static final int LIMIT = 100;

	private final JdbcClient jdbcClient;

	public ContentListRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public List<ContentListRow> findContents(ContentListQuery query) {
		return safeQuery(List::of, () -> {
			Sql sql = buildWhere(query);
			return jdbcClient.sql("""
					SELECT c.short_code, c.thumbnail_url, c.caption, c.posted_at, c.content_type,
					       a.handle, a.display_name, a.profile_image_url, a.followers,
					       s.views, s.likes, s.comments, s.hype_score, s.captured_at,
					       an.ad_type,
					       an.detected_product_categories::text AS product_categories_json,
					       jsonb_array_length(an.detected_brands) AS brand_count
					""" + sql.fromWhere + orderBy(query.sort()) + "\nLIMIT " + LIMIT)
					.params(sql.params)
					.query(ContentListRow.class)
					.list();
		});
	}

	public long countContents(ContentListQuery query) {
		return safeQuery(() -> 0L, () -> {
			Sql sql = buildWhere(query);
			return jdbcClient.sql("SELECT count(*)" + sql.fromWhere)
					.params(sql.params)
					.query(Long.class)
					.single();
		});
	}

	private record Sql(String fromWhere, Map<String, Object> params) {
	}

	private Sql buildWhere(ContentListQuery q) {
		StringBuilder sb = new StringBuilder("""

				FROM contents c
				JOIN accounts a ON a.handle = c.account_handle
				JOIN content_analyses an ON an.short_code = c.short_code
				JOIN LATERAL (
				  SELECT views, likes, comments, hype_score, captured_at
				  FROM content_metric_snapshots m
				  WHERE m.short_code = c.short_code AND m.captured_at < :cutoff
				  ORDER BY m.captured_at DESC LIMIT 1
				) s ON true
				WHERE c.posted_at >= :startInstant AND c.posted_at < :cutoff
				""");
		Map<String, Object> params = new HashMap<>();
		params.put("startInstant", q.startInstant());
		params.put("cutoff", q.cutoff());
		if (q.contentType() != null) {
			sb.append(" AND c.content_type = :contentType");
			params.put("contentType", q.contentType());
		}
		if (q.follower() != null) {
			sb.append(" AND a.followers >= :followerMin AND a.followers < :followerMax");
			params.put("followerMin", q.follower().min);
			params.put("followerMax", q.follower().max);
		}
		if (q.q() != null && !q.q().isBlank()) {
			sb.append(" AND c.caption ILIKE :caption");
			params.put("caption", "%" + q.q() + "%");
		}
		if (q.adType() != null) {
			sb.append(" AND an.ad_type = :adType");
			params.put("adType", q.adType());
		}
		if (q.mainCategory() != null) {
			sb.append(" AND an.main_category = :mainCategory");
			params.put("mainCategory", q.mainCategory());
		}
		if (q.midCategory() != null) {
			sb.append(" AND jsonb_exists(an.sub_categories, :midCategory)");
			params.put("midCategory", q.midCategory());
		}
		if (q.subCategory() != null) {
			sb.append(" AND jsonb_exists(an.sub_categories, :subCategory)");
			params.put("subCategory", q.subCategory());
		}
		if (q.distributor() != null) {
			// 유통사 저장 컬럼 신설 전 — 지정 시 매칭 0 (신설 후 이 줄을 jsonb_exists(an.detected_distributors, :distributor)로 교체)
			sb.append(" AND false");
		}
		return new Sql(sb.toString(), params);
	}

	private String orderBy(String sort) {
		return switch (sort) {
			case "latest" -> "\nORDER BY c.posted_at DESC, c.short_code";
			case "engagement" ->
				"\nORDER BY (s.likes + s.comments)::numeric / NULLIF(s.views, 0) DESC NULLS LAST, c.short_code";
			default -> "\nORDER BY s.hype_score DESC NULLS LAST, c.short_code";
		};
	}

	private <T> T safeQuery(Supplier<T> fallback, Supplier<T> query) {
		try {
			return query.get();
		} catch (DataAccessException e) {
			log.warn("목록 조회 실패, 빈 값으로 대체합니다: {}", e.getMessage());
			return fallback.get();
		}
	}
}
