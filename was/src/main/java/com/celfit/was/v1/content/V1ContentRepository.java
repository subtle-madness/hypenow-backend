package com.celfit.was.v1.content;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 6.1 조회 — contents ⋈ content_analyses(분석 완료만 노출 ∧ 뷰티만(is_beauty=true) ∧
 * 시점 편향 없는 분만(metric_timeliness timely 또는 미분류 레거시 NULL — late_backfill·immature 제외)) ⋈ accounts.
 * 중분류 확장 매칭·유통사 슬러그 해석은 어휘 테이블(beauty_taxonomy·beauty_distributors)로
 * SQL 안에서 처리한다 — Java 상수 하드코딩 없음(어휘는 생산자 소유, §4-4).
 */
@Repository
public class V1ContentRepository {

	private final JdbcClient jdbcClient;

	public V1ContentRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public List<ContentCardRow> findCards(V1ContentQuery q) {
		Sql sql = buildWhere(q);
		return jdbcClient.sql(ContentCardRow.SELECT + sql.fromWhere + orderBy(q.sort())
						+ "\nLIMIT " + q.limit() + " OFFSET " + q.offset())
				.params(sql.params)
				.query(ContentCardRow.class)
				.list();
	}

	public long countCards(V1ContentQuery q) {
		Sql sql = buildWhere(q);
		return jdbcClient.sql("SELECT count(*)" + sql.fromWhere)
				.params(sql.params).query(Long.class).single();
	}

	/** meta.distributors 옵션 — 어휘 테이블 전체, id(슬러그) 오름차순 (스펙 6.1). */
	public List<Map<String, Object>> findDistributorOptions() {
		return jdbcClient.sql("SELECT slug, name FROM beauty_distributors ORDER BY slug")
				.query((rs, i) -> Map.<String, Object>of("id", rs.getString("slug"), "name", rs.getString("name")))
				.list();
	}

	private record Sql(String fromWhere, Map<String, Object> params) {
	}

	private Sql buildWhere(V1ContentQuery q) {
		StringBuilder sb = new StringBuilder("""

				FROM contents c
				JOIN content_analyses an ON an.short_code = c.short_code
				JOIN accounts a ON a.handle = c.account_handle
				LEFT JOIN image_assets it ON it.kind = 'thumbnail' AND it.key = c.short_code
				LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = a.handle
				WHERE c.posted_at >= :start AND c.posted_at < :end
				  AND c.content_type = :contentType
				  AND an.is_beauty = true
				  -- 랭킹은 시점 편향 없는 분만 노출 (2026-07-21 PO 결정): late_backfill(늦크롤 지표 상향
				  -- 편향)·immature(미성숙 하향 편향)는 제외, timely만 노출. 단 시점 미분류 레거시(NULL,
				  -- V33 이전 기분석분 — 백필 편향과 무관)는 비회귀로 유지. 백필분은 인플루언서 상세
				  -- recentContents(LEFT JOIN)에서만 노출된다.
				  AND (an.metric_timeliness = 'timely' OR an.metric_timeliness IS NULL)
				""");
		Map<String, Object> params = new HashMap<>();
		params.put("start", q.startInstant());
		params.put("end", q.endExclusive());
		params.put("contentType", q.contentType());
		if (q.mainCategory() != null) {
			sb.append(" AND an.main_category = :mainCategory");
			params.put("mainCategory", q.mainCategory());
		}
		if (q.midCategory() != null) {
			// 중분류 → 소속 소분류 확장 매칭 (스펙 5.5) — 어휘는 beauty_taxonomy가 원천
			// (텍스트 블록 들여쓰기 스트립이 선행 공백을 지우므로 빈 첫 줄로 개행을 명시한다)
			sb.append("""

					AND EXISTS (SELECT 1 FROM beauty_taxonomy t
					            WHERE t.main_value = :mainCategory AND t.mid_label = :midCategory
					              AND jsonb_exists(an.sub_categories, t.sub_label))""");
			params.put("midCategory", q.midCategory());
		}
		if (q.subCategory() != null) {
			sb.append(" AND jsonb_exists(an.sub_categories, :subCategory)");
			params.put("subCategory", q.subCategory());
		}
		if (q.follower() != null) {
			long min = switch (q.follower()) {
				case "3k-10k" -> 3_000; case "10k-30k" -> 10_000; default -> 30_000; };
			long max = switch (q.follower()) {
				case "3k-10k" -> 10_000; case "10k-30k" -> 30_000; default -> 50_000; };
			sb.append(" AND a.followers >= :fMin AND a.followers < :fMax");
			params.put("fMin", min);
			params.put("fMax", max);
		}
		if (q.keyword() != null) {
			sb.append(" AND c.caption ILIKE :kw");
			params.put("kw", "%" + q.keyword() + "%");
		}
		if (q.adType() != null) {
			sb.append(" AND an.ad_type = :adType");
			params.put("adType", q.adType());
		}
		if (q.distributorId() != null) {
			if (q.distributorId().equals("none")) {
				sb.append(" AND (an.detected_distributors IS NULL OR an.detected_distributors = '[]'::jsonb)");
			} else {
				// 슬러그 → 이름 해석 후 jsonb 매칭 (저장값은 한글명 — 어휘 테이블이 사전)
				sb.append("""

						AND EXISTS (SELECT 1 FROM beauty_distributors bd
						            WHERE bd.slug = :distSlug
						              AND jsonb_exists(an.detected_distributors, bd.name))""");
				params.put("distSlug", q.distributorId());
			}
		}
		return new Sql(sb.toString(), params);
	}

	/** 동점 2차 정렬은 항상 short_code 오름차순 (스펙 6.1 안정 정렬). */
	private String orderBy(String sort) {
		return switch (sort) {
			case "latest" -> "\nORDER BY c.posted_at DESC, c.short_code";
			case "views" -> "\nORDER BY c.views DESC NULLS LAST, c.short_code";
			default -> "\nORDER BY c.hype_score DESC NULLS LAST, c.short_code";
		};
	}
}
