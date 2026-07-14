package com.celfit.was.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.contract.analysis.AccountCategoryStat;
import com.celfit.contract.analysis.AccountContentPoint;
import com.celfit.contract.analysis.AccountSummary;
import com.celfit.was.IntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class InfluencerDetailRepositoryTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	InfluencerDetailRepository repository;

	@BeforeEach
	void setUpTables() {
		// accounts: PostDetailRepositoryTest와 동일 형상(패키지 분리로 의도적 중복)
		jdbcTemplate.execute("DROP TABLE IF EXISTS accounts");
		jdbcTemplate.execute("""
				CREATE TABLE accounts (
				    handle            text PRIMARY KEY,
				    display_name      text,
				    profile_image_url text,
				    followers         bigint
				)""");
		jdbcTemplate.update("""
				INSERT INTO accounts VALUES ('glow', '글로우', 'https://pic/glow.jpg', 42555)
				""");

		// 인플루언서 상세 미러 3종: V10__account_detail_tables.sql과 동일 형상
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_content_series");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_category_stats");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_summaries");
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
				CREATE TABLE account_category_stats (
				    account_handle text NOT NULL,
				    main_group     text NOT NULL,
				    content_count  bigint,
				    PRIMARY KEY (account_handle, main_group)
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE account_content_series (
				    short_code     text PRIMARY KEY,
				    account_handle text NOT NULL,
				    posted_at      timestamptz,
				    content_type   text,
				    views          bigint,
				    likes          bigint,
				    comments       bigint,
				    sponsored      boolean
				)""");
		jdbcTemplate.execute(
				"CREATE INDEX idx_account_content_series_handle ON account_content_series (account_handle)");

		jdbcTemplate.update("""
				INSERT INTO account_summaries VALUES (
				    'glow', 42555, 312, 486, '건성 8년차',
				    12, 10, 'views', 30600, 1.3,
				    4.3, 5515, 90,
				    'up', 18, 25000, 29500,
				    2, 30600, 26900, 12,
				    6, 2, '2026-07-11T00:00:00Z',
				    '2026-07-12T00:00:00Z', 2.4
				)""");
		jdbcTemplate.update("""
				INSERT INTO account_category_stats VALUES
				 ('glow', '스킨케어', 7),
				 ('glow', '메이크업', 3),
				 ('glow', '클렌징', 3)
				""");
		jdbcTemplate.update("""
				INSERT INTO account_content_series VALUES
				 ('g1', 'glow', '2026-07-01T00:00:00Z', 'reels', 1000, 100, 10, false),
				 ('g2', 'glow', '2026-07-05T00:00:00Z', 'feed', NULL, 300, 30, true),
				 ('g3', 'glow', '2026-07-08T00:00:00Z', 'reels', 2000, 150, 15, false)
				""");
	}

	@Test
	void handle로_요약_1행을_계약_record로_읽는다() {
		Optional<AccountSummary> found = repository.findSummary("glow");
		assertThat(found).isPresent();
		assertThat(found.get().metric()).isEqualTo("views");
		assertThat(found.get().avgErPct()).isEqualByComparingTo(new BigDecimal("4.3"));
		assertThat(found.get().adDropPct()).isEqualTo(12);
		assertThat(found.get().avgIntervalDays()).isEqualByComparingTo(new BigDecimal("2.4"));
	}

	@Test
	void 없는_handle이면_empty다() {
		assertThat(repository.findSummary("nope")).isEmpty();
	}

	@Test
	void 카테고리_믹스는_개수_내림차순_라벨_오름차순이다() {
		List<AccountCategoryStat> stats = repository.findCategoryStats("glow");
		assertThat(stats).extracting(AccountCategoryStat::mainGroup)
				.containsExactly("스킨케어", "메이크업", "클렌징");
	}

	@Test
	void 시계열은_올린_순이다() {
		List<AccountContentPoint> series = repository.findSeries("glow");
		assertThat(series).extracting(AccountContentPoint::shortCode).containsExactly("g1", "g2", "g3");
		assertThat(series.get(1).views()).isNull();
		assertThat(series.get(1).sponsored()).isTrue();
	}

	@Test
	void 미러_테이블이_없으면_빈_값으로_저하한다() {
		jdbcTemplate.execute("DROP TABLE account_content_series");
		jdbcTemplate.execute("DROP TABLE account_category_stats");
		jdbcTemplate.execute("DROP TABLE account_summaries");
		jdbcTemplate.execute("DROP TABLE accounts");

		assertThat(repository.findSummary("glow")).isEmpty();
		assertThat(repository.findAccount("glow")).isEmpty();
		assertThat(repository.findCategoryStats("glow")).isEmpty();
		assertThat(repository.findSeries("glow")).isEmpty();
	}
}
