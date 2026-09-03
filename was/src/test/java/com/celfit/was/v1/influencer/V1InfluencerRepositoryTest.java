package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.v1.content.ContentCardRow;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** findRecentCards 실DB 검증 — 상세 조회의 "최근 12개"는 분석 미완 게시물도 포함한다(목록 6.1과 달리). */
class V1InfluencerRepositoryTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	V1InfluencerRepository repository;

	@BeforeEach
	void setUpTables() {
		jdbcTemplate.execute("DROP TABLE IF EXISTS content_analyses");
		jdbcTemplate.execute("DROP TABLE IF EXISTS contents");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_summaries");
		jdbcTemplate.execute("DROP TABLE IF EXISTS accounts");
		jdbcTemplate.execute("DROP TABLE IF EXISTS image_assets");
		jdbcTemplate.execute("DROP TABLE IF EXISTS group_purchase_judgments");
		jdbcTemplate.execute("""
				CREATE TABLE accounts (
				    handle            text PRIMARY KEY,
				    display_name      text,
				    profile_image_url text,
				    followers         bigint,
				    external_link     text
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE account_summaries (
				    handle                   text PRIMARY KEY,
				    followers                bigint,
				    follows_count            bigint,
				    posts_count              bigint,
				    biography                text,
				    analyzed_count           bigint,
				    views_count              bigint,
				    metric                   text,
				    avg_views                bigint,
				    views_per_follower       numeric,
				    avg_er_pct               numeric,
				    avg_likes                bigint,
				    avg_comments             bigint,
				    trend_direction          text,
				    trend_change_pct         integer,
				    trend_older_avg          bigint,
				    trend_newer_avg          bigint,
				    sponsored_count          bigint,
				    organic_avg              bigint,
				    ad_avg                   bigint,
				    ad_drop_pct              integer,
				    comparison_organic_count bigint,
				    comparison_ad_count      bigint,
				    last_ad_posted_at        timestamptz,
				    last_posted_at           timestamptz,
				    avg_interval_days        numeric
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE contents (
				    short_code          text PRIMARY KEY,
				    account_handle      text NOT NULL,
				    thumbnail_url       text,
				    caption             text,
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
		jdbcTemplate.execute("""
				CREATE TABLE content_analyses (
				    short_code            text PRIMARY KEY,
				    main_category         text,
				    sub_categories        jsonb,
				    ad_type               text,
				    detected_brands       jsonb,
				    detected_products     jsonb,
				    detected_distributors jsonb,
				    is_beauty             boolean
				)""");
		// image_assets 사본 DDL (analytics 아카이브 잡이 채우는 테이블 — Task 2 DDL과 동일 형상)
		jdbcTemplate.execute("""
				CREATE TABLE image_assets (
				    kind        text NOT NULL,
				    key         text NOT NULL,
				    object_path text NOT NULL,
				    source_name text NOT NULL,
				    archived_at timestamptz NOT NULL DEFAULT now(),
				    PRIMARY KEY (kind, key)
				)""");
		// group_purchase_judgments 사본 DDL(analytics Task 1 V20260903110541과 동일 형상) —
		// ContentCardRow.SELECT의 gpj 조인 재료. groupPurchase(6.4) 검증 재료는 아래 개별 INSERT.
		jdbcTemplate.execute("""
				CREATE TABLE group_purchase_judgments (
				    short_code          text PRIMARY KEY,
				    verdict             boolean,
				    tier                text NOT NULL,
				    reason              text,
				    judged_caption_hash text NOT NULL,
				    judged_at           timestamptz NOT NULL,
				    model               text
				)""");

		jdbcTemplate.update("""
				INSERT INTO accounts (handle, display_name, profile_image_url, followers, external_link) VALUES
				 ('alpha', '알파', 'https://pic/alpha.jpg', 5000, null)
				""");

		// a1(분석 완료, 이른 게시), a2(분석 미완, 더 최신 게시), a3(비뷰티 확정, 가장 최신 게시).
		jdbcTemplate.update("""
				INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, posted_at, content_type,
				  video_duration, original_url, views, likes, comments, hype_score, metric_captured_at,
				  hype_score_precise) VALUES
				 ('a1', 'alpha', 'https://thumb/a1.jpg', '분석 완료 릴스', '2026-07-02T03:00:00Z', 'reels',
				  20, 'https://ig/a1', 1000, 100, 10, 500, '2026-07-05T03:00:00Z', 500),
				 ('a2', 'alpha', 'https://thumb/a2.jpg', '분석 미완 릴스', '2026-07-04T03:00:00Z', 'reels',
				  15, 'https://ig/a2', 9999, 999, 99, 999, '2026-07-07T03:00:00Z', 999),
				 ('a3', 'alpha', 'https://thumb/a3.jpg', '일상 브이로그', '2026-07-06T03:00:00Z', 'reels',
				  18, 'https://ig/a3', 3000, 300, 30, 700, '2026-07-09T03:00:00Z', 700)
				""");

		// a1은 뷰티로 분석 완료, a3는 비뷰티로 분석 완료 — a2는 content_analyses에 없음(미분석).
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, main_category, sub_categories, ad_type,
				  detected_brands, detected_products, detected_distributors, is_beauty) VALUES
				 ('a1', 'makeup', '["아이라이너"]'::jsonb, 'organic',
				  '[{"name":"브랜드A"}]'::jsonb, NULL, NULL, true),
				 ('a3', NULL, NULL, 'organic', NULL, NULL, NULL, false)
				""");

		// groupPurchase(6.4, 2026-09-03) 검증 재료 — a1은 verdict=true(판정 확정), a3는 verdict
		// NULL(미판정 — LLM 실패·잡 대기), a2는 판정 행 자체가 없음(잡이 아직 못 본 신규 게시물).
		// 셋 다 groupPurchase는 a1만 true여야 한다(NULL·행 없음은 동일하게 false로 취급 — 신뢰성 우선).
		jdbcTemplate.update("""
				INSERT INTO group_purchase_judgments (short_code, verdict, tier, reason,
				  judged_caption_hash, judged_at) VALUES
				 ('a1', true, 'RULE', '공동구매', 'h1', now()),
				 ('a3', NULL, 'LLM', NULL, 'h3', now())
				""");
	}

	@Test
	void 최근_카드는_뷰티_판정과_무관하게_실제_최신_12개를_반환한다() {
		List<ContentCardRow> rows = repository.findRecentCards("alpha");

		// a3(비뷰티 확정)도 포함 — 성과 지표 모수와 동일한 "실제 최신 12개"(07-28 결정).
		// posted_at DESC: a3(07-06) → a2(07-04, 미분석) → a1(07-02)
		assertThat(rows).extracting(ContentCardRow::shortCode).containsExactly("a3", "a2", "a1");
	}

	@Test
	void 분석_미완_게시물은_분석_필드가_null이고_지표는_채워진다() {
		ContentCardRow a2 = repository.findRecentCards("alpha").stream()
				.filter(r -> r.shortCode().equals("a2")).findFirst().orElseThrow();

		assertThat(a2.mainCategory()).isNull();
		assertThat(a2.adType()).isNull();
		assertThat(a2.brandsJson()).isNull();
		assertThat(a2.views()).isEqualTo(9999L);
		assertThat(a2.handle()).isEqualTo("alpha");
		assertThat(a2.followers()).isEqualTo(5000L);
	}

	@Test
	void groupPurchase는_verdict_true인_게시물만_true다() {
		List<ContentCardRow> rows = repository.findRecentCards("alpha");
		var byCode = rows.stream()
				.collect(Collectors.toMap(ContentCardRow::shortCode, r -> r));

		assertThat(byCode.get("a1").groupPurchase()).isTrue(); // verdict=true
		assertThat(byCode.get("a3").groupPurchase()).isFalse(); // verdict NULL(미판정)
		assertThat(byCode.get("a2").groupPurchase()).isFalse(); // 판정 행 자체가 없음
	}

	@Test
	void 프로필_이미지는_아카이브되면_img_경로_아니면_원본() {
		// 원본 URL이 설정된 프로필 조회 — 아카이브 없으면 원본 반환(fallback)
		V1InfluencerRepository.ProfileRow profile = repository.findProfile("alpha").orElseThrow();
		assertThat(profile.profileImageUrl()).isEqualTo("https://pic/alpha.jpg");

		// 아카이브 추가 후 재조회 — COALESCE가 /img/ 경로 선택
		jdbcTemplate.update("""
				INSERT INTO image_assets (kind, key, object_path, source_name)
				VALUES ('profile', 'alpha', 'profile/alpha.jpg', 'alpha.jpg')
				""");

		profile = repository.findProfile("alpha").orElseThrow();
		assertThat(profile.profileImageUrl()).isEqualTo("/img/profile/alpha.jpg");
	}
}
