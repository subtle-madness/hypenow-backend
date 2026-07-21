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
		jdbcTemplate.execute("DROP TABLE IF EXISTS image_assets");
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

		jdbcTemplate.update("""
				INSERT INTO accounts (handle, display_name, profile_image_url, followers) VALUES
				 ('alpha', '알파', 'https://pic/alpha.jpg', 5000)
				""");

		// a1(분석 완료, 이른 게시), a2(분석 미완, 더 최신 게시), a3(비뷰티 확정, 가장 최신 게시).
		jdbcTemplate.update("""
				INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, posted_at, content_type,
				  video_duration, original_url, views, likes, comments, hype_score, metric_captured_at) VALUES
				 ('a1', 'alpha', 'https://thumb/a1.jpg', '분석 완료 릴스', '2026-07-02T03:00:00Z', 'reels',
				  20, 'https://ig/a1', 1000, 100, 10, 500, '2026-07-05T03:00:00Z'),
				 ('a2', 'alpha', 'https://thumb/a2.jpg', '분석 미완 릴스', '2026-07-04T03:00:00Z', 'reels',
				  15, 'https://ig/a2', 9999, 999, 99, 999, '2026-07-07T03:00:00Z'),
				 ('a3', 'alpha', 'https://thumb/a3.jpg', '일상 브이로그', '2026-07-06T03:00:00Z', 'reels',
				  18, 'https://ig/a3', 3000, 300, 30, 700, '2026-07-09T03:00:00Z')
				""");

		// a1은 뷰티로 분석 완료, a3는 비뷰티로 분석 완료 — a2는 content_analyses에 없음(미분석).
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, main_category, sub_categories, ad_type,
				  detected_brands, detected_products, detected_distributors, is_beauty) VALUES
				 ('a1', 'makeup', '["아이라이너"]'::jsonb, 'organic',
				  '[{"name":"브랜드A"}]'::jsonb, NULL, NULL, true),
				 ('a3', NULL, NULL, 'organic', NULL, NULL, NULL, false)
				""");
	}

	@Test
	void 최근_카드는_분석_미완은_포함하되_비뷰티는_제외한다() {
		List<ContentCardRow> rows = repository.findRecentCards("alpha");

		// a3(비뷰티) 제외, a2(미분석)는 유지 — posted_at DESC: a2(07-04) → a1(07-02)
		assertThat(rows).extracting(ContentCardRow::shortCode).containsExactly("a2", "a1");
		assertThat(rows).extracting(ContentCardRow::shortCode).doesNotContain("a3");
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
