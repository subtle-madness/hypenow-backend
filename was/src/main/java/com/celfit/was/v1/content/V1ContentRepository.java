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

	/**
	 * meta.distributors 옵션 — 요청 축의 어휘, id(슬러그) 오름차순 (스펙 6.1).
	 * 축 인자는 2026-09-01 FE 버티컬 피드백 #5로 도입 — 구(08-31)엔 뷰티 고정이었다.
	 * 어휘 테이블이 두 축의 유통사를 함께 담으므로(뷰티 2·F&amp;B 11) 축 필터가 없으면
	 * 상대 축 유통사가 드롭다운에 섞인다.
	 */
	public List<Map<String, Object>> findDistributorOptions(String axis) {
		return jdbcClient.sql(
						"SELECT slug, name FROM beauty_distributors WHERE axis = :axis ORDER BY slug")
				.param("axis", axis)
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
			// 대분류 필터가 있으면 축 게이트(is_beauty)를 걸지 않는다 (2026-08-31 F&B 서빙 개방 §2).
			// 생산자 불변식 "main_category 있음 ⇒ 축 확정"이 근거: 뷰티 slug면 is_beauty=true가
			// 파생돼 있어 동치이고, F&B slug면 is_beauty=false라 기존 게이트와 모순이라 빼야 나온다.
			sb.append(" AND an.main_category = :mainCategory");
			params.put("mainCategory", q.mainCategory());
		} else if ("fnb".equals(q.vertical())) {
			// 버티컬 전체(2026-09-01 FE 요청) — F&B는 "main이 F&B slug"가 곧 축 판정이라 IN이 정확.
			sb.append(" AND an.main_category IN (:verticalMains)");
			params.put("verticalMains", com.celfit.was.v1.common.MainCategories.FNB);
		} else {
			// 무필터·vertical=beauty = 뷰티(기본 화면과 동치) — is_beauty가 뷰티 축 판정 그 자체라
			// IN(뷰티 slug)보다 넓다(대분류 미도출 뷰티 게시물 포함, 기존 노출 유지).
			sb.append(" AND an.is_beauty = true");
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

	/**
	 * 동점 2차 정렬은 항상 short_code 오름차순 (스펙 6.1 안정 정렬).
	 * 기본(hype) 정렬 키는 2026-07-30부터 hype_score_precise(소수) — hype_score(정수)는 랭킹 경로
	 * n=110,488+ 규모라 동점 밀도가 낮아 계정처럼 정렬이 알파벳순에 지배되는 문제는 없었지만,
	 * 소수점 노출 자체가 표시·정렬 일원화가 목적이라 정렬 키도 표시값을 그대로 따른다
	 * (스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md §10). short_code 동점 처리는
	 * 그대로 무해하게 남는다.
	 */
	private String orderBy(String sort) {
		return switch (sort) {
			case "latest" -> "\nORDER BY c.posted_at DESC, c.short_code";
			case "views" -> "\nORDER BY c.views DESC NULLS LAST, c.short_code";
			default -> "\nORDER BY c.hype_score_precise DESC NULLS LAST, c.short_code";
		};
	}
}
