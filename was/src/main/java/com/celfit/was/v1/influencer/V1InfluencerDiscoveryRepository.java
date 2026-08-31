package com.celfit.was.v1.influencer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 6.21 발굴 목록 조회 — 모수는 account_summaries 보유 계정(최근 12창 분석 계정) ⋈ accounts.
 * 광고 판정 정본은 content_analyses.ad_type='sponsored'(캡션 분류) — series.sponsored(raw 플래그)는
 * 쓰지 않는다(리포트 개편 07-27과 동일 결정). 중분류 확장은 어휘 테이블(beauty_taxonomy)로 SQL 안에서
 * 처리(§4-4). 필터·정렬·페이지·total은 본 쿼리 1회(count(*) OVER () — 0행일 때만 count 폴백),
 * 반환 페이지 핸들에 대해서만 보강 4쿼리
 * (카테고리 비중·협업 브랜드·최근 썸네일·유효 팔로워 시계열)를 더 친다. 유효 팔로워 자체는
 * SQL이 아니라 Java(EffectiveFollowers, 6.21/6.22 공용 산식)에서 계산한다(스펙 7절 17번).
 */
@Repository
public class V1InfluencerDiscoveryRepository {

	// 뷰티 게시물 비율 게이트 (07-30) — 계정 단위 뷰티 판정(crawler influencer.beauty_class)이
	// bio 키워드·자기신고 카테고리에 낚여 육아·다이어트·여행·피트니스 계정을 뷰티 인플루언서로
	// 잘못 서빙하는 사례(0% 구간 스팟체크 20개 중 오판 17개)를 게시물 실측으로 걸러낸다.
	// analyzed_count(분석 완료 창 내 게시물 수) < 8이면 판단 근거가 얇아 게이트를 보류(통과시킨다) —
	// 표본이 적을수록 비율 하나로 계정을 판정하는 게 오히려 오분류를 늘린다.
	private static final int MIN_ANALYZED = 8;
	// 20%는 기존 카테고리 게이트(build() 내 mainCategory 임계값)와 동일 기준을 그대로 재사용한다.
	private static final double MIN_BEAUTY_RATIO_PERCENT = 20.0;

	// cp(최신 태그라인)·sp(협찬 수)·br(뷰티 비율)는 q·sponsored 필터·게이트가 참조하므로
	// count 쿼리에도 함께 붙인다. findCardsByHandles(6.23 유사 카드 재사용)도 이 조인을
	// 공유하지만, 그쪽 후보는 findSimilarHandles에서 이미 게이트를 통과한 핸들만 들어오므로
	// 별도 WHERE 재적용은 하지 않는다.
	//
	// sp·br·mainCategory 게이트(account_category_share)는 사전집계 matview(analytics
	// V20260827045100, 입력 변경 잡 후 DerivedViewRefresher가 갱신 — 스펙
	// 2026-08-27-discovery-precompute-design.md). 요청 시점 풀 집계가 사라져 2026-07-30의
	// sp 핸들 푸시다운 분기도 존재 이유가 소멸했다 — 두 경로가 같은 FROM을 쓴다.
	private static final String FROM_JOINS = """

			FROM account_summaries su
			JOIN accounts a ON a.handle = su.handle
			LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = a.handle
			LEFT JOIN LATERAL (SELECT aa.tagline FROM account_analyses aa
			                   WHERE aa.handle = su.handle
			                   ORDER BY aa.analyzed_at DESC LIMIT 1) cp ON true
			LEFT JOIN account_sponsored_counts sp ON sp.account_handle = su.handle
			LEFT JOIN account_beauty_ratio br ON br.account_handle = su.handle""";

	private final JdbcClient jdbcClient;

	public V1InfluencerDiscoveryRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public List<CardRow> findCards(V1InfluencerDiscoveryQuery q) {
		Sql sql = build(q);
		return jdbcClient.sql("""
						SELECT a.handle, a.display_name,
						       COALESCE('/img/' || ip.object_path, a.profile_image_url) AS profile_image_url,
						       a.followers,
						       su.posts_count, su.follows_count, su.biography, cp.tagline,
						       su.views_per_follower, su.avg_er_pct AS avg_er_pct,
						       su.avg_views, su.avg_likes, su.avg_comments, su.avg_hype_score,
						       COALESCE(sp.cnt, 0) AS sponsored_count, su.email, su.avg_hype_score_precise,
						       count(*) OVER () AS total_count
						""" + sql.fromJoins + "\n" + sql.where + orderBy(q.sort())
						+ "\nLIMIT " + q.limit() + " OFFSET " + q.offset())
				.params(sql.params)
				.query(CardRow.class)
				.list();
	}

	public long countCards(V1InfluencerDiscoveryQuery q) {
		Sql sql = build(q);
		return jdbcClient.sql("SELECT count(*)" + sql.fromJoins + "\n" + sql.where)
				.params(sql.params).query(Long.class).single();
	}

	/**
	 * 핸들 목록 카드 일괄 조회(6.23 유사 카드 재사용) — 필터·정렬 없음, 순서는 호출부가 복원.
	 * FROM 절은 발굴 목록과 동일(sp가 사전집계 matview라 핸들 푸시다운 변형이 더는 필요 없다).
	 * total_count는 발굴 목록 페이징 전용 개념이라 여기서는 NULL.
	 */
	public List<CardRow> findCardsByHandles(List<String> handles) {
		if (handles.isEmpty()) {
			return List.of();
		}
		return jdbcClient.sql("""
				SELECT a.handle, a.display_name,
				       COALESCE('/img/' || ip.object_path, a.profile_image_url) AS profile_image_url,
				       a.followers,
				       su.posts_count, su.follows_count, su.biography, cp.tagline,
				       su.views_per_follower, su.avg_er_pct AS avg_er_pct,
				       su.avg_views, su.avg_likes, su.avg_comments, su.avg_hype_score,
				       COALESCE(sp.cnt, 0) AS sponsored_count, su.email, su.avg_hype_score_precise,
				       NULL::bigint AS total_count
				""" + FROM_JOINS + """

				WHERE a.handle IN (:handles)
				""").param("handles", handles).query(CardRow.class).list();
	}

	private record Sql(String fromJoins, String where, Map<String, Object> params) {
	}

	private Sql build(V1InfluencerDiscoveryQuery q) {
		String fromJoins = FROM_JOINS;
		StringBuilder where = new StringBuilder("WHERE true");
		Map<String, Object> params = new HashMap<>();
		// 축 분기 (2026-08-31 F&B 서빙 개방 §3): 무필터·뷰티축 필터 = 뷰티 계정(기본 화면 불변),
		// F&B축 필터 = F&B 계정. COALESCE 방향이 다르다 — beauty는 true(롤링 창에서 구 미러가 축을
		// 안 채운 기존 행은 전부 뷰티 모수 출신), fnb는 false(미러 전엔 F&B 계정이 미러에 없다).
		boolean fnbAxis = com.celfit.was.v1.common.MainCategories.isFnb(q.mainCategory());
		if (fnbAxis) {
			where.append(" AND COALESCE(a.fnb, false)");
			// 뷰티 게시물 비율 게이트는 F&B축에 적용하지 않는다 — F&B 계정은 뷰티 비율이 0이라
			// 걸면 전멸한다. 오판 계정 방어는 아래 F&B 비중 20% 게이트가 같은 역할(실측 게시물 기반).
		} else {
			where.append(" AND COALESCE(a.beauty, true)");
			// 뷰티 게시물 비율 게이트 — 뷰티 경로에 항상 적용(스펙 §4). 분석 표본이 minAnalyzed
			// 미만이면 통과, 그 이상이면 뷰티 비율이 minBeautyRatio 이상인 계정만 통과.
			// NULLIF(analyzed_count, 0) 필수 — Postgres는 OR 단축 평가를 보장하지 않아 실행 계획에 따라
			// 두 번째 항도 평가될 수 있다. 창 내 게시물이 전부 is_beauty NULL(캡션·썸네일 둘 다 없음)이면
			// account_beauty_ratio에 행은 존재하되 analyzed_count=0이라 division by zero로 500이 난다.
			// NULLIF로 그 경우 두 번째 항을 NULL로 만들면 첫 항(TRUE)과 OR돼 TRUE — 표본 부족 보류와 동일 취급.
			where.append("""

					  AND (COALESCE(br.analyzed_count, 0) < :minAnalyzed
					       OR 100.0 * br.beauty_count / NULLIF(br.analyzed_count, 0) >= :minBeautyRatio)""");
			params.put("minAnalyzed", MIN_ANALYZED);
			params.put("minBeautyRatio", MIN_BEAUTY_RATIO_PERCENT);
		}
		if (q.mainCategory() != null) {
			// 비중 임계값 매칭(포함 여부 아님) — 산식은 account_category_share가 사전계산
			// (분모·round가 categoryShares.pct와 동일 — matview 정의 주석 참조, 스펙 6.21).
			// 대상 대분류 게시물 0건이면 행 부재 = EXISTS false — 기존 COALESCE(...,0)>=20 false와 동치.
			where.append("""

					  AND EXISTS (SELECT 1 FROM account_category_share cs
					              WHERE cs.account_handle = su.handle
					                AND cs.main_category = :mainCategory AND cs.pct >= 20)""");
			params.put("mainCategory", q.mainCategory());
		}
		if (q.midCategory() != null) {
			// 중분류 → 소속 소분류 확장 매칭 (스펙 5.5) — 어휘는 beauty_taxonomy가 원천
			where.append("""

					  AND EXISTS (SELECT 1 FROM account_content_series s
					              JOIN content_analyses an ON an.short_code = s.short_code
					              JOIN beauty_taxonomy t ON t.main_value = :mainCategory
					                                    AND t.mid_label = :midCategory
					              WHERE s.account_handle = su.handle
					                AND jsonb_exists(an.sub_categories, t.sub_label))""");
			params.put("midCategory", q.midCategory());
		}
		if (q.subCategory() != null) {
			where.append("""

					  AND EXISTS (SELECT 1 FROM account_content_series s
					              JOIN content_analyses an ON an.short_code = s.short_code
					              WHERE s.account_handle = su.handle
					                AND jsonb_exists(an.sub_categories, :subCategory))""");
			params.put("subCategory", q.subCategory());
		}
		if (q.follower() != null) {
			long min = switch (q.follower()) {
				case "500-3k" -> 500; case "3k-10k" -> 3_000;
				case "10k-30k" -> 10_000; default -> 30_000; };
			long max = switch (q.follower()) {
				case "500-3k" -> 3_000; case "3k-10k" -> 10_000;
				case "10k-30k" -> 30_000; default -> 50_000; };
			where.append(" AND a.followers >= :fMin AND a.followers < :fMax");
			params.put("fMin", min);
			params.put("fMax", max);
		}
		if (q.activityDays() != null) {
			// 마지막 업로드 경과일 ≤ N(inclusive) — 기준 날짜는 KST 달력 날짜(스펙 3.4)
			where.append("""

					  AND su.last_posted_at IS NOT NULL
					  AND (now() AT TIME ZONE 'Asia/Seoul')::date
					      - (su.last_posted_at AT TIME ZONE 'Asia/Seoul')::date <= :activityDays""");
			params.put("activityDays", q.activityDays());
		}
		if (q.sponsored() != null) {
			switch (q.sponsored()) {
				case "none" -> where.append(" AND COALESCE(sp.cnt, 0) = 0");
				case "1-2" -> where.append(" AND COALESCE(sp.cnt, 0) BETWEEN 1 AND 2");
				case "3-5" -> where.append(" AND COALESCE(sp.cnt, 0) BETWEEN 3 AND 5");
				default -> where.append(" AND COALESCE(sp.cnt, 0) >= 6");
			}
		}
		if (q.contactOpen()) {
			// email은 biography 정규식 파싱(V46, 스펙 2026-07-30-influencer-email-from-bio)으로 채워진다.
			where.append(" AND su.email IS NOT NULL");
		}
		for (int i = 0; i < q.keywords().size(); i++) {
			// 키워드 전부(AND) 부분일치 — 대상: handle·displayName·bio·tagline·캡션·협업 브랜드명·소분류 라벨
			String p = "kw" + i;
			where.append("""

					  AND (a.handle ILIKE :%1$s OR a.display_name ILIKE :%1$s
					       OR su.biography ILIKE :%1$s OR cp.tagline ILIKE :%1$s
					       OR EXISTS (SELECT 1 FROM account_content_series s
					                  JOIN contents c ON c.short_code = s.short_code
					                  WHERE s.account_handle = su.handle AND c.caption ILIKE :%1$s)
					       OR EXISTS (SELECT 1 FROM account_content_series s
					                  JOIN content_analyses an ON an.short_code = s.short_code
					                                          AND an.ad_type = 'sponsored'
					                  WHERE s.account_handle = su.handle
					                    AND EXISTS (SELECT 1 FROM jsonb_array_elements(
					                                       COALESCE(an.detected_brands, '[]'::jsonb)) b
					                                WHERE b->>'name' ILIKE :%1$s))
					       OR EXISTS (SELECT 1 FROM account_content_series s
					                  JOIN content_analyses an ON an.short_code = s.short_code
					                  WHERE s.account_handle = su.handle
					                    AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(
					                                       COALESCE(an.sub_categories, '[]'::jsonb)) sc
					                                WHERE sc ILIKE :%1$s)))""".formatted(p));
			params.put(p, "%" + q.keywords().get(i) + "%");
		}
		return new Sql(fromJoins, where.toString(), params);
	}

	/**
	 * 전부 내림차순, 동점 2차 정렬은 id(=handle) 오름차순 (스펙 6.21 안정 정렬).
	 * hype 정렬 키는 2026-07-30부터 avg_hype_score_precise(소수, 출력 매핑 반영 — 스펙
	 * 2026-07-30-hype-score-v3-decay-after-mapping-design.md §10)다. 이전에는 표시값 avg_hype_score
	 * (정수)가 상위권(상위 1% 54개가 4개 값으로 압축)에서 동점을 대량으로 만들어 정렬이 사실상
	 * handle 알파벳순에 지배되는 결함이 있어 정렬만 반올림 전 avg_hype_raw로 분리했었다(§9 하위절)
	 * — avg_hype_score_precise 자체가 이미 소수라 그 우회가 더는 필요 없다(표시=정렬 일원화).
	 */
	private String orderBy(String sort) {
		return switch (sort) {
			case "views" -> "\nORDER BY su.avg_views DESC NULLS LAST, a.handle";
			case "followers" -> "\nORDER BY a.followers DESC NULLS LAST, a.handle";
			case "hype" -> "\nORDER BY su.avg_hype_score_precise DESC NULLS LAST, a.handle";
			default -> "\nORDER BY su.views_per_follower DESC NULLS LAST, a.handle";
		};
	}

	/** categoryShares 재료 — 분모는 창 내 "뷰티 판정 + 대분류 보유" 게시물 수, 비중 내림차순. */
	public List<ShareRow> findShares(List<String> handles) {
		if (handles.isEmpty()) {
			return List.of();
		}
		return jdbcClient.sql("""
						SELECT account_handle, main_category, round(100.0 * cnt / total)::int AS pct
						FROM (SELECT s.account_handle, an.main_category, count(*) AS cnt,
						             sum(count(*)) OVER (PARTITION BY s.account_handle) AS total
						      FROM account_content_series s
						      JOIN content_analyses an ON an.short_code = s.short_code
						      WHERE s.account_handle IN (:handles)
						        AND an.is_beauty IS TRUE AND an.main_category IS NOT NULL
						      GROUP BY s.account_handle, an.main_category) x
						ORDER BY account_handle, pct DESC, main_category
						""").param("handles", handles).query(ShareRow.class).list();
	}

	/** collaboratedBrands 재료 — 협찬(ad_type='sponsored') 콘텐츠의 detected_brands name, 빈도 내림차순. */
	public List<BrandRow> findBrands(List<String> handles) {
		if (handles.isEmpty()) {
			return List.of();
		}
		return jdbcClient.sql("""
						SELECT account_handle, name FROM (
						  SELECT s.account_handle, b->>'name' AS name, count(*) AS cnt
						  FROM account_content_series s
						  JOIN content_analyses an ON an.short_code = s.short_code
						                          AND an.ad_type = 'sponsored'
						  CROSS JOIN LATERAL jsonb_array_elements(
						                     COALESCE(an.detected_brands, '[]'::jsonb)) b
						  WHERE s.account_handle IN (:handles) AND b->>'name' IS NOT NULL
						  GROUP BY s.account_handle, b->>'name') x
						ORDER BY account_handle, cnt DESC, name
						""").param("handles", handles).query(BrandRow.class).list();
	}

	/** recentThumbs 재료 — postedAt 내림차순 최대 4개. 썸네일은 아카이브 /img/ 경로 우선(카드 관용구). */
	public List<ThumbRow> findThumbs(List<String> handles) {
		if (handles.isEmpty()) {
			return List.of();
		}
		return jdbcClient.sql("""
						SELECT account_handle, short_code, thumbnail_url, content_type, main_category,
						       ad_type, posted_at, views, likes, comments
						FROM (SELECT s.account_handle, s.short_code,
						             COALESCE('/img/' || it.object_path, c.thumbnail_url) AS thumbnail_url,
						             s.content_type, an.main_category,
						             COALESCE(an.ad_type, 'organic') AS ad_type,
						             s.posted_at, s.views, s.likes, s.comments,
						             row_number() OVER (PARTITION BY s.account_handle
						                                ORDER BY s.posted_at DESC, s.short_code) AS rn
						      FROM account_content_series s
						      LEFT JOIN contents c ON c.short_code = s.short_code
						      LEFT JOIN content_analyses an ON an.short_code = s.short_code
						      LEFT JOIN image_assets it ON it.kind = 'thumbnail' AND it.key = s.short_code
						      WHERE s.account_handle IN (:handles)) x
						WHERE rn <= 4
						ORDER BY account_handle, rn
						""").param("handles", handles).query(ThumbRow.class).list();
	}

	/** 유효 팔로워 재료 — 페이지 핸들의 창 내 시계열(순서 무관, 산식이 평균이라). 계산은 Java(EffectiveFollowers). */
	public List<EngagementRow> findEngagements(List<String> handles) {
		if (handles.isEmpty()) {
			return List.of();
		}
		return jdbcClient.sql("""
						SELECT account_handle, views, likes, comments
						FROM account_content_series
						WHERE account_handle IN (:handles)
						""").param("handles", handles).query(EngagementRow.class).list();
	}

	public record CardRow(String handle, String displayName, String profileImageUrl, Long followers,
			Long postsCount, Long followsCount, String biography,
			String tagline, BigDecimal viewsPerFollower, BigDecimal avgErPct, Long avgViews,
			Long avgLikes, Long avgComments, Long avgHypeScore, Long sponsoredCount, String email,
			// 하입 스코어 소수점 노출(2026-07-30) — avgHypeScore(정수, 값·의미 불변)는 그대로 두고
			// 표시·정렬은 이 필드로 옮긴다(스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md §10).
			BigDecimal avgHypeScorePrecise,
			// findCards의 count(*) OVER () — 필터 전체 건수(LIMIT 전). findCardsByHandles는 null
			// (2026-08-27 count 통합 — countCards는 0행 폴백 전용으로 존치).
			Long totalCount) {
	}

	public record ShareRow(String accountHandle, String mainCategory, Integer pct) {
	}

	public record BrandRow(String accountHandle, String name) {
	}

	public record ThumbRow(String accountHandle, String shortCode, String thumbnailUrl,
			String contentType, String mainCategory, String adType, OffsetDateTime postedAt,
			Long views, Long likes, Long comments) {
	}

	public record EngagementRow(String accountHandle, Long views, Long likes, Long comments) {
	}
}
