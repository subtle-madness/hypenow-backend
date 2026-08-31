package com.celfit.was;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 캐시 통합 테스트용 6.1 콘텐츠 경로 최소 형상 DDL·시드 — 분석 DB 형상 사본(필요 컬럼만,
 * {@link com.celfit.was.v1.content.ContentCardRow#SELECT}·{@code V1ContentRepository.buildWhere}
 * 참조 컬럼 기준으로 검증됨). account_summaries·account_analyses·account_content_series·
 * beauty_taxonomy는 6.5 인플루언서 리포트 경로(캐시 통합 테스트: 404 비캐싱 + 서비스 경유 캐시
 * 히트 검증)용 — handle='glow' 1행만 account_summaries에 채우고 나머지 보강 조회 테이블은
 * {@code V1InfluencerReportRepository}의 findLatestCopy/findSeries/findCategories/findBrands가
 * 참조하는 형태만 갖춘 빈 테이블(어셈블러가 빈 리스트·null copy를 정상 처리 — V1InfluencerReportAssembler
 * 참조)로 둔다.
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
		jdbc.execute("DROP TABLE IF EXISTS account_analyses");
		jdbc.execute("DROP TABLE IF EXISTS account_content_series");
		jdbc.execute("DROP TABLE IF EXISTS beauty_taxonomy");
		jdbc.execute("""
				CREATE TABLE contents (
				    short_code          text PRIMARY KEY,
				    account_handle      text,
				    caption             text,
				    thumbnail_url       text,
				    posted_at           timestamptz,
				    content_type        text,
				    video_duration      numeric,
				    original_url        text,
				    views               bigint,
				    likes               bigint,
				    comments            bigint,
				    hype_score          bigint,
				    metric_captured_at  timestamptz,
				    hype_score_precise  numeric
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
		// axis는 findDistributorOptions의 뷰티 축 필터가 읽는다 (2026-08-31 F&B 어휘 추가)
		jdbc.execute("CREATE TABLE beauty_distributors (slug text PRIMARY KEY, name text,"
				+ " axis text NOT NULL DEFAULT 'beauty')");
		// 6.5 인플루언서 리포트 경로용. handle='glow' 1행만 채우고(캐시 히트 검증이 UPDATE로
		// 변경을 걸 대상) 나머지 handle 조회는 전부 empty → findSummary 404(캐시 통합 테스트 F).
		// 컬럼은 V1InfluencerReportRepository.SummaryRow SELECT 목록과 1:1.
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
		// findLatestCopy(계정 LLM 카피, 없으면 카피 필드만 null)·findSeries/findCategories/findBrands
		// (시계열·보강 조회) 참조 테이블 — 전부 비워둔다. 어셈블러가 빈 리스트·null copy를 정상
		// 처리하므로(V1InfluencerReportAssembler) 리포트 조립 자체는 account_summaries 1행만으로 성립.
		jdbc.execute("""
				CREATE TABLE account_analyses (
				    handle      text,
				    analyzed_at timestamptz,
				    tagline     text,
				    summary     text,
				    trend_note  text,
				    chart_note  text,
				    traits      jsonb,
				    ad_headline text,
				    pace_note   text
				)""");
		jdbc.execute("""
				CREATE TABLE account_content_series (
				    short_code     text,
				    account_handle text,
				    posted_at      timestamptz,
				    content_type   text,
				    views          bigint,
				    likes          bigint,
				    comments       bigint
				)""");
		jdbc.execute("CREATE TABLE beauty_taxonomy (main_value text, main_label text)");
		jdbc.execute("""
				INSERT INTO accounts (handle, display_name, profile_image_url, followers)
				VALUES ('glow', '글로우', null, 20000)""");
		jdbc.execute("""
				INSERT INTO account_summaries (handle, analyzed_count, posts_count, metric, avg_views,
				    views_per_follower, avg_er_pct, avg_likes, avg_comments, trend_direction,
				    sponsored_count, organic_avg, ad_avg, ad_drop_pct, comparison_organic_count,
				    comparison_ad_count, last_ad_posted_at, last_posted_at, avg_interval_days)
				VALUES ('glow', 12, 30, 'views', 50000, 2.5, 4.0, 1000, 50, 'up',
				    2, 45000, 30000, 33, 10,
				    2, null, now() - interval '1 day', 3.5)""");
		// hype 내림차순: c1(90) → c2(80). c1은 video_duration을 채워 캐시 히트 단언(A)에서
		// BigDecimal scale 왕복까지 같이 잡는다(2026-07-29 리뷰 누적 체크리스트 A).
		// hype_score_precise는 hype_score와 같은 순서를 보존하는 소수값(2026-07-30 정렬 키 전환 —
		// hype_score DESC → hype_score_precise DESC, ContentCardRow.SELECT 참조).
		jdbc.execute("""
				INSERT INTO contents (short_code, account_handle, caption, thumbnail_url, posted_at,
				    content_type, video_duration, original_url, views, likes, comments, hype_score,
				    metric_captured_at, hype_score_precise)
				VALUES
				  ('c1', 'glow', '수분크림 리뷰', null, now() - interval '1 day', 'reels', 15.5, null, 1000, 100, 10, 90, now(), 90.1234),
				  ('c2', 'glow', '선크림 리뷰',   null, now() - interval '2 day', 'reels', null, null,  800,  80,  8, 80, now(), 80.5)""");
		jdbc.execute("""
				INSERT INTO content_analyses (short_code, is_beauty, metric_timeliness)
				VALUES ('c1', true, 'timely'), ('c2', true, 'timely')""");
	}
}
