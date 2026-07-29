package com.celfit.was;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 캐시 통합 테스트용 6.1 콘텐츠 경로 최소 형상 DDL·시드 — 분석 DB 형상 사본(필요 컬럼만,
 * {@link com.celfit.was.v1.content.ContentCardRow#SELECT}·{@code V1ContentRepository.buildWhere}
 * 참조 컬럼 기준으로 검증됨). account_summaries는 6.5 인플루언서 리포트 404(캐시 미적재) 검증용으로
 * 빈 테이블만 얹는다({@code V1InfluencerReportRepository.findSummary} 참조 컬럼).
 */
public final class ContentCacheSeed {

	private ContentCacheSeed() {
	}

	public static void reset(JdbcTemplate jdbc) {
		jdbc.execute("DROP TABLE IF EXISTS content_analyses");
		jdbc.execute("DROP TABLE IF EXISTS contents");
		jdbc.execute("DROP TABLE IF EXISTS accounts");
		jdbc.execute("DROP TABLE IF EXISTS image_assets");
		jdbc.execute("DROP TABLE IF EXISTS beauty_distributors");
		jdbc.execute("DROP TABLE IF EXISTS account_summaries");
		jdbc.execute("""
				CREATE TABLE contents (
				    short_code         text PRIMARY KEY,
				    account_handle     text,
				    caption            text,
				    thumbnail_url      text,
				    posted_at          timestamptz,
				    content_type       text,
				    video_duration     numeric,
				    original_url       text,
				    views              bigint,
				    likes              bigint,
				    comments           bigint,
				    hype_score         bigint,
				    metric_captured_at timestamptz
				)""");
		jdbc.execute("""
				CREATE TABLE content_analyses (
				    short_code            text PRIMARY KEY,
				    is_beauty             boolean,
				    metric_timeliness     text,
				    main_category         text,
				    sub_categories        jsonb,
				    ad_type               text,
				    detected_brands       jsonb,
				    detected_products     jsonb,
				    detected_distributors jsonb
				)""");
		jdbc.execute("""
				CREATE TABLE accounts (
				    handle            text PRIMARY KEY,
				    display_name      text,
				    profile_image_url text,
				    followers         bigint
				)""");
		jdbc.execute("CREATE TABLE image_assets (kind text NOT NULL, key text NOT NULL, object_path text)");
		jdbc.execute("CREATE TABLE beauty_distributors (slug text PRIMARY KEY, name text)");
		// 6.5 인플루언서 리포트 404 경로(캐시 통합 테스트 F)용 — 항상 비워둔다(행 없음 = findSummary
		// empty → 404). 컬럼은 V1InfluencerReportRepository.SummaryRow SELECT 목록과 1:1.
		jdbc.execute("""
				CREATE TABLE account_summaries (
				    handle                    text PRIMARY KEY,
				    analyzed_count            bigint,
				    posts_count               bigint,
				    metric                    text,
				    avg_views                 bigint,
				    views_per_follower        numeric,
				    avg_er_pct                numeric,
				    avg_likes                 bigint,
				    avg_comments              bigint,
				    trend_direction           text,
				    sponsored_count           bigint,
				    organic_avg               bigint,
				    ad_avg                    bigint,
				    ad_drop_pct               integer,
				    comparison_organic_count  bigint,
				    comparison_ad_count       bigint,
				    last_ad_posted_at         timestamptz,
				    last_posted_at            timestamptz,
				    avg_interval_days         numeric
				)""");
		jdbc.execute("INSERT INTO accounts VALUES ('glow', '글로우', null, 20000)");
		// hype 내림차순: c1(90) → c2(80). c1은 video_duration을 채워 캐시 히트 단언(A)에서
		// BigDecimal scale 왕복까지 같이 잡는다(2026-07-29 리뷰 누적 체크리스트 A).
		jdbc.execute("""
				INSERT INTO contents VALUES
				  ('c1', 'glow', '수분크림 리뷰', null, now() - interval '1 day', 'reels', 15.5, null, 1000, 100, 10, 90, now()),
				  ('c2', 'glow', '선크림 리뷰',   null, now() - interval '2 day', 'reels', null, null,  800,  80,  8, 80, now())""");
		jdbc.execute("""
				INSERT INTO content_analyses (short_code, is_beauty, metric_timeliness)
				VALUES ('c1', true, 'timely'), ('c2', true, 'timely')""");
	}
}
