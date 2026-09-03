package com.celfit.was.v2.influencer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 6.22·6.23 발굴 리포트 v2 조회 — analysis DB 미러 읽기 전용(분석 결과끼리 조인은 §4-4 허용). */
@Repository
public class V2InfluencerReportRepository {

	// 뷰티 게시물 비율 게이트 (07-30) — 발굴 목록(V1InfluencerDiscoveryRepository)과 동일 기준.
	// 추천 표면(유사 인플루언서)이므로 게이트에 걸리는 계정이 후보로 튀어나오면 안 된다.
	// F&B 축은 이 게이트 대신 accounts.fnb 게이트(스펙 2026-09-01) — F&B 계정은 뷰티 비율이 0이다.
	private static final int MIN_ANALYZED = 8;
	private static final double MIN_BEAUTY_RATIO_PERCENT = 20.0;

	private final JdbcClient jdbcClient;

	public V2InfluencerReportRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** account_summaries 1행 — 없으면 empty → 404(인플루언서 없음). */
	public Optional<SummaryRow> findSummary(String handle) {
		return jdbcClient.sql("""
				SELECT followers, analyzed_count, posts_count, avg_views, views_per_follower,
				       avg_er_pct, avg_likes, avg_comments, last_posted_at, avg_interval_days
				FROM account_summaries
				WHERE handle = :h
				""").param("h", handle).query(SummaryRow.class).optional();
	}

	/** 신 스키마 카피 최신 1행 — perf_summary가 있는 행만(구 스키마 행은 "리포트 미생성" = 404).
	 *  tagline·perf·content는 잡 가드가 비-null 보장, ad_summary만 nullable(AdSituation.INSUFFICIENT). */
	public Optional<CopyRow> findLatestCopy(String handle) {
		return jdbcClient.sql("""
				SELECT tagline, traits::text AS traits_json, perf_summary, content_summary, ad_summary
				FROM account_analyses
				WHERE handle = :h AND perf_summary IS NOT NULL
				ORDER BY analyzed_at DESC
				LIMIT 1
				""").param("h", handle).query(CopyRow.class).optional();
	}

	/** 창 내 시계열 — 올린 순(posted_at ASC, 2차 short_code). 추이 2종·성장세·유효 팔로워·광고 간격 재료.
	 *  sponsored 정본은 캡션 분류(content_analyses.ad_type='sponsored') — v1 리포트와 동일 결정(07-27). */
	public List<SeriesRow> findSeries(String handle) {
		return jdbcClient.sql("""
				SELECT s.posted_at, s.content_type, s.views, s.likes, s.comments,
				       COALESCE(an.ad_type = 'sponsored', false) AS sponsored
				FROM account_content_series s
				LEFT JOIN content_analyses an ON an.short_code = s.short_code
				WHERE s.account_handle = :h
				ORDER BY s.posted_at, s.short_code
				""").param("h", handle).query(SeriesRow.class).list();
	}

	/** contentMix.categories — 창 내 콘텐츠 × 분석 대분류, label은 main_label 폴백 main_category. */
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

	/**
	 * ads.brands — 이 계정의 브랜드별 협찬 게시물(contentIds, 올린 순)과 풀 내 같은 브랜드 협업 계정
	 * 상위 5(otherInfluencers, 협업 수 내림차순 — 스펙 7절 22번 백엔드 확정). pairs에서 (브랜드,게시물)
	 * DISTINCT — detected_brands 배열에 같은 브랜드가 중복 기재돼도 contentIds·cnt가 안 부풀도록
	 * (traits Jaccard DISTINCT 교훈과 동일). others의 cnt도 count(DISTINCT s2.short_code)로 게시물
	 * 단위 중복 제거(mine과 대칭). JSON 집계는 CopyRow.traitsJson과 같은 ::text 관용구.
	 *
	 * 09-03 성능 수리: others가 브랜드별 상관 서브쿼리(SubPlan)였을 때 플래너가 브랜드마다
	 * content_analyses(운영 309MB) 전체 순차 스캔 + jsonb 전개를 반복했다 — 운영 실측 브랜드 21개
	 * 계정 7.3초(동시 2건 13.7초), 단계 로그의 시간 전부가 이 메서드. 지금은 ① 내 브랜드명 배열을
	 * `?|`로 한 번에 짚고(GIN 식 인덱스 idx_content_analyses_brand_names_gin — analytics
	 * V20260903083555, 술어 식이 인덱스 식과 글자 단위로 같아야 붙는다) ② 브랜드×계정 집계를
	 * 조인으로 붙여 스캔이 브랜드 수와 무관하게 1회다. 단일 참조 CTE는 플래너가 도로 상관 서브쿼리로
	 * 인라인하므로(실측: MATERIALIZED 없이 동일 3.2초) mine은 MATERIALIZED. 로컬 스냅샷(협찬 6.5만 행)
	 * 실측 브랜드 49개 3,168ms → 8ms(인덱스 없이도 885ms), 결과 전량 동일.
	 * `??|`는 Spring named-param 파서와 pgjdbc가 각각 `?`를 바인드 자리로 읽어 필요한 이중
	 * 이스케이프 — DB에 닿는 SQL은 `?|`. 완전 동률(협업 수·최신 게시일)은 handle 오름차순으로
	 * 고정(원본은 비결정 순서).
	 */
	public List<BrandCollabRow> findBrandCollabs(String handle) {
		return jdbcClient.sql("""
				WITH pairs AS (
				  SELECT DISTINCT b->>'name' AS name, s.short_code, s.posted_at
				  FROM account_content_series s
				  JOIN content_analyses an ON an.short_code = s.short_code AND an.ad_type = 'sponsored'
				  CROSS JOIN LATERAL jsonb_array_elements(COALESCE(an.detected_brands, '[]'::jsonb)) b
				  WHERE s.account_handle = :h AND b->>'name' IS NOT NULL
				),
				mine AS MATERIALIZED (
				  SELECT name, count(*) AS cnt,
				         jsonb_agg(short_code ORDER BY posted_at)::text AS content_ids_json
				  FROM pairs GROUP BY name
				),
				others AS (
				  SELECT b2->>'name' AS name, s2.account_handle AS handle,
				         count(DISTINCT s2.short_code) AS cnt, max(s2.posted_at) AS last_at
				  FROM account_content_series s2
				  JOIN content_analyses an2 ON an2.short_code = s2.short_code AND an2.ad_type = 'sponsored'
				  CROSS JOIN LATERAL jsonb_array_elements(COALESCE(an2.detected_brands, '[]'::jsonb)) b2
				  WHERE s2.account_handle <> :h
				    AND jsonb_path_query_array(an2.detected_brands, '$[*].name')
				          ??| (SELECT array_agg(name)::text[] FROM mine)
				    AND b2->>'name' IN (SELECT name FROM mine)
				  GROUP BY 1, 2
				),
				ranked AS (
				  SELECT name, handle, cnt, last_at,
				         row_number() OVER (PARTITION BY name ORDER BY cnt DESC, last_at DESC, handle) AS rn
				  FROM others
				),
				others_agg AS (
				  SELECT name, jsonb_agg(handle ORDER BY cnt DESC, last_at DESC, handle)::text AS others_json
				  FROM ranked WHERE rn <= 5 GROUP BY name
				)
				SELECT m.name, m.cnt, m.content_ids_json, COALESCE(o.others_json, '[]') AS others_json
				FROM mine m LEFT JOIN others_agg o ON o.name = m.name
				ORDER BY m.cnt DESC, m.name
				""").param("h", handle).query(BrandCollabRow.class).list();
	}

	/**
	 * 대상 계정의 유사 추천 축 — F&B 단독 계정만 fnb(true). 혼합(뷰티∧F&B)·레거시(null)는
	 * beauty: 기존 뷰티 화면 불변 우선(스펙 2026-09-01 §4). COALESCE 방향은 발굴 무필터
	 * (beauty→true, fnb→false)와 동일 논리 — 미러 갱신 전 구 행은 전부 뷰티 모수 출신.
	 */
	public boolean findFnbAxis(String handle) {
		return jdbcClient.sql("""
				SELECT COALESCE(fnb, false) AND NOT COALESCE(beauty, true)
				FROM accounts WHERE handle = :h
				""").param("h", handle).query(Boolean.class).optional().orElse(false);
	}

	/** 유사 인플루언서 핸들 — 혼합 점수 = 0.6×traits Jaccard + 0.4×카테고리 믹스 히스토그램 교집합.
	 *  축(fnbAxis)은 후보 풀 자체를 가른다(스펙 2026-09-01): peers·cats CTE가 축 인지 뷰를
	 *  axis='fnb'/'beauty'로 필터하므로, 대분류가 축을 결정하는 성질상 다른 축 계정은 피어
	 *  카테고리가 '미분류'로 떨어져 후보에 섞이지 않는다. 후보 게이트도 축별로 갈린다 — 아래 참조.
	 *  같은 피어 카테고리 내에서 컷 0.30 미달 제외, 점수 내림차순·팔로워 근접·handle 순 상위 10
	 *  (07-28 유사도 v2 — 스펙 6.23의 9는 10으로 변경 확정). 점수는 정렬·컷 전용이라 반환하지 않는다.
	 *  휴면 계정(최근 업로드 3개월 밖 또는 미확인) 후보 제외 — 휴면 정의 정본은 아래 su 조인 조건
	 *  하나뿐이다(저장 플래그·스케줄러 없음, 다른 표면에서 숨길 때 이 조건을 재사용). 기준 계정
	 *  자신은 휴면이어도 목록을 받는다.
	 *  카피 없는 계정은 후보 제외(LATERAL INNER). 믹스는 집합 기반 CTE — 상관 서브쿼리는 운영 규모
	 *  실측 1,981ms 성능 절벽(집합 기반 85ms, 컷 0.30 근거는 운영 dry-run: 10위 점수 최솟값 0.400).
	 *  scored MATERIALIZED는 점수식의 WHERE/ORDER BY 이중 평가 방지. 카드 조립은
	 *  발굴 목록(6.21) 표면 재사용 — 기준 계정이 풀에 없으면 빈 목록.
	 *  뷰티 게시물 비율 게이트(07-30)도 후보 단계에서 적용 — 발굴 목록과 동일 기준, br LEFT JOIN.
	 *  07-31 최적화: account_peer_stats는 me·c에서, account_category_stats는 my_shares·cand_mix에서
	 *  각각 2번씩 참조돼 일반 VIEW라 매번 인라인 재계산되고 있었다 — peers·cats MATERIALIZED CTE로
	 *  한 번만 평가하도록 묶었다. test DB 핸들 13개 전수 결과 완전 일치(순서 포함) 확인, 중앙값
	 *  1560.7ms → 753.4ms(약 2.1배), content_analyses(94,433행)·account_content_series(77,943행)
	 *  Seq Scan 6회 → 4회로 감소(EXPLAIN ANALYZE 실측). */
	public List<String> findSimilarHandles(String handle, boolean fnbAxis) {
		// 후보 게이트 분기(발굴 build()와 동일 패턴, 스펙 2026-09-01 §4): 뷰티 축은 기존 뷰티 비율
		// 게이트(결과 불변), F&B 축은 accounts.fnb 게이트 — F&B 계정은 뷰티 비율 0이라 걸면 전멸.
		String candidateGate = fnbAxis
				? "  AND COALESCE(ac.fnb, false)\n"
				: """
						  -- NULLIF(analyzed_count, 0) 필수 — OR 단축 평가 미보장 + 창 전체가 is_beauty NULL인
						  -- 계정 가능성(V1InfluencerDiscoveryRepository와 동일 이유, division by zero 방지).
						  AND (COALESCE(br.analyzed_count, 0) < :minAnalyzed
						       OR 100.0 * br.beauty_count / NULLIF(br.analyzed_count, 0) >= :minBeautyRatio)
						""";
		var spec = jdbcClient.sql("""
				WITH peers AS MATERIALIZED (
				  SELECT handle, peer_category FROM account_peer_axis_stats WHERE axis = :axis
				),
				cats AS MATERIALIZED (
				  SELECT account_handle, main_group, content_count FROM account_category_stats
				  WHERE axis = :axis
				),
				-- MATERIALIZED 필수(09-01 운영 실측): 1행짜리 me가 오추정(피어 est 1행)에 밀려 후보별
				-- 재평가(peers 12,055행 스캔 × 후보 12,054회 ≈ 12초)로 풀렸다 — 강제 1회 평가로 고정.
				me AS MATERIALIZED (
				  SELECT p.peer_category, ac.followers, la.traits
				  FROM peers p
				  JOIN accounts ac ON ac.handle = p.handle
				  JOIN LATERAL (SELECT traits FROM account_analyses
				                WHERE handle = p.handle ORDER BY analyzed_at DESC LIMIT 1) la ON true
				  WHERE p.handle = :h
				),
				my_shares AS (
				  SELECT main_group,
				         content_count::numeric / sum(content_count) OVER () AS share
				  FROM cats
				  WHERE account_handle = :h
				),
				cand_mix AS (
				  SELECT s.account_handle, sum(LEAST(ms.share, s.share)) AS mix_overlap
				  FROM (SELECT account_handle, main_group,
				               content_count::numeric / sum(content_count)
				                 OVER (PARTITION BY account_handle) AS share
				        FROM cats) s
				  JOIN my_shares ms ON ms.main_group = s.main_group
				  GROUP BY s.account_handle
				),
				scored AS MATERIALIZED (
				  SELECT c.handle, ac.followers, me.followers AS my_followers,
				         0.6 * COALESCE(
				           (SELECT count(DISTINCT t.value) FROM jsonb_array_elements_text(la.traits) t
				             WHERE t.value IN (SELECT value FROM jsonb_array_elements_text(me.traits)))::numeric
				           / NULLIF((SELECT count(DISTINCT value) FROM (
				               SELECT value FROM jsonb_array_elements_text(la.traits)
				               UNION ALL SELECT value FROM jsonb_array_elements_text(me.traits)) u), 0), 0)
				         + 0.4 * COALESCE(cm.mix_overlap, 0) AS score
				  FROM peers c
				  JOIN me ON c.peer_category = me.peer_category
				  JOIN accounts ac ON ac.handle = c.handle
				  JOIN account_summaries su ON su.handle = c.handle
				       AND su.last_posted_at >= now() - interval '3 months'
				  JOIN LATERAL (SELECT traits FROM account_analyses
				                WHERE handle = c.handle ORDER BY analyzed_at DESC LIMIT 1) la ON true
				  LEFT JOIN cand_mix cm ON cm.account_handle = c.handle
				  LEFT JOIN account_beauty_ratio br ON br.account_handle = c.handle
				  WHERE c.handle <> :h
				""" + candidateGate + """
				)
				SELECT handle
				FROM scored
				WHERE score >= 0.30
				ORDER BY score DESC, abs(followers - my_followers) ASC, handle ASC
				LIMIT 10
				""")
				.param("h", handle)
				.param("axis", fnbAxis ? "fnb" : "beauty");
		if (!fnbAxis) {
			spec = spec.param("minAnalyzed", MIN_ANALYZED).param("minBeautyRatio", MIN_BEAUTY_RATIO_PERCENT);
		}
		return spec.query(String.class).list();
	}

	public record SummaryRow(Long followers, Long analyzedCount, Long postsCount, Long avgViews,
			BigDecimal viewsPerFollower, BigDecimal avgErPct, Long avgLikes, Long avgComments,
			OffsetDateTime lastPostedAt, BigDecimal avgIntervalDays) {
	}

	public record CopyRow(String tagline, String traitsJson, String perfSummary,
			String contentSummary, String adSummary) {
	}

	public record SeriesRow(OffsetDateTime postedAt, String contentType, Long views, Long likes,
			Long comments, Boolean sponsored) {
	}

	public record CategoryRow(String label, Long cnt) {
	}

	public record BrandCollabRow(String name, Long cnt, String contentIdsJson, String othersJson) {
	}
}
