package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.v1.content.ContentCardRow;
import java.util.List;
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
		jdbcTemplate.execute("DROP TABLE IF EXISTS accounts");
		jdbcTemplate.execute("""
				CREATE TABLE accounts (
				    handle            text PRIMARY KEY,
				    display_name      text,
				    profile_image_url text,
				    followers         bigint
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE contents (
				    short_code         text PRIMARY KEY,
				    account_handle     text NOT NULL,
				    thumbnail_url      text,
				    caption            text,
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
		jdbcTemplate.execute("""
				CREATE TABLE content_analyses (
				    short_code            text PRIMARY KEY,
				    main_category         text,
				    sub_categories        jsonb,
				    ad_type               text,
				    detected_brands       jsonb,
				    detected_products     jsonb,
				    detected_distributors jsonb
				)""");

		jdbcTemplate.update("""
				INSERT INTO accounts (handle, display_name, profile_image_url, followers) VALUES
				 ('alpha', '알파', 'https://pic/alpha.jpg', 5000)
				""");

		// a1(분석 완료, 이른 게시), a2(분석 미완, 더 최신 게시).
		jdbcTemplate.update("""
				INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, posted_at, content_type,
				  video_duration, original_url, views, likes, comments, hype_score, metric_captured_at) VALUES
				 ('a1', 'alpha', 'https://thumb/a1.jpg', '분석 완료 릴스', '2026-07-02T03:00:00Z', 'reels',
				  20, 'https://ig/a1', 1000, 100, 10, 500, '2026-07-05T03:00:00Z'),
				 ('a2', 'alpha', 'https://thumb/a2.jpg', '분석 미완 릴스', '2026-07-04T03:00:00Z', 'reels',
				  15, 'https://ig/a2', 9999, 999, 99, 999, '2026-07-07T03:00:00Z')
				""");

		// a1만 분석됨 — a2는 content_analyses에 없음.
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, main_category, sub_categories, ad_type,
				  detected_brands, detected_products, detected_distributors) VALUES
				 ('a1', 'makeup', '["아이라이너"]'::jsonb, 'organic',
				  '[{"name":"브랜드A"}]'::jsonb, NULL, NULL)
				""");
	}

	@Test
	void 최근_카드는_분석_미완_게시물도_포함하고_posted_at_내림차순이다() {
		List<ContentCardRow> rows = repository.findRecentCards("alpha");

		// 분석 미완 a2도 포함 — posted_at DESC: a2(07-04) → a1(07-02)
		assertThat(rows).extracting(ContentCardRow::shortCode).containsExactly("a2", "a1");
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
}
