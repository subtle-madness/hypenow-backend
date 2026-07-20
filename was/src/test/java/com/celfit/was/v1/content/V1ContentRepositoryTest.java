package com.celfit.was.v1.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class V1ContentRepositoryTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	V1ContentRepository repository;

	@BeforeEach
	void setUpTables() {
		// V31 형상 DDL 사본 — contents.metric_captured_at·hype_score(테이블 직컬럼),
		// 어휘 테이블 beauty_taxonomy(V30)·beauty_distributors(V30+V31 slug) 포함.
		jdbcTemplate.execute("DROP TABLE IF EXISTS content_analyses");
		jdbcTemplate.execute("DROP TABLE IF EXISTS contents");
		jdbcTemplate.execute("DROP TABLE IF EXISTS accounts");
		jdbcTemplate.execute("DROP TABLE IF EXISTS beauty_taxonomy");
		jdbcTemplate.execute("DROP TABLE IF EXISTS beauty_distributors");
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
		jdbcTemplate.execute("""
				CREATE TABLE beauty_taxonomy (
				    main_value text NOT NULL,
				    main_label text NOT NULL,
				    mid_label  text NOT NULL,
				    sub_label  text NOT NULL,
				    main_order int  NOT NULL,
				    mid_order  int  NOT NULL,
				    sub_order  int  NOT NULL,
				    PRIMARY KEY (main_value, mid_label, sub_label)
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE beauty_distributors (
				    name text PRIMARY KEY,
				    sort int  NOT NULL,
				    slug text NOT NULL UNIQUE
				)""");

		// 어휘 시드: 아이메이크업(아이라이너)·립메이크업(립틴트), 유통사 2종(slug 포함)
		jdbcTemplate.update("""
				INSERT INTO beauty_taxonomy (main_value, main_label, mid_label, sub_label, main_order, mid_order, sub_order) VALUES
				 ('makeup', '메이크업', '아이메이크업', '아이라이너', 3, 3, 1),
				 ('makeup', '메이크업', '립메이크업', '립틴트', 3, 1, 1)
				""");
		jdbcTemplate.update("""
				INSERT INTO beauty_distributors (name, sort, slug) VALUES
				 ('올리브영', 1, 'oliveyoung'),
				 ('다이소', 2, 'daiso')
				""");

		// 계정 2: alpha(5000) · beta(20000)
		jdbcTemplate.update("""
				INSERT INTO accounts (handle, display_name, profile_image_url, followers) VALUES
				 ('alpha', '알파', 'https://pic/alpha.jpg', 5000),
				 ('beta', '베타', 'https://pic/beta.jpg', 20000)
				""");

		// 콘텐츠 6 — posted_at은 KST 정오 = UTC 03:00. 기간 [2026-07-01, 2026-07-10].
		// r1~r3·r9 릴스, f1·f2 피드(views NULL 규약이나 f2는 정렬 검증용으로 값 부여).
		jdbcTemplate.update("""
				INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, posted_at, content_type,
				  video_duration, original_url, views, likes, comments, hype_score, metric_captured_at) VALUES
				 ('r1', 'alpha', 'https://thumb/r1.jpg', '아이라이너 릴스', '2026-07-02T03:00:00Z', 'reels',
				  20, 'https://ig/r1', 1000, 100, 10, 500, '2026-07-05T03:00:00Z'),
				 ('r2', 'beta', 'https://thumb/r2.jpg', '토너 릴스', '2026-07-03T03:00:00Z', 'reels',
				  25, 'https://ig/r2', 3000, 300, 30, 900, '2026-07-06T03:00:00Z'),
				 ('r3', 'alpha', 'https://thumb/r3.jpg', '분석 미완 릴스', '2026-07-04T03:00:00Z', 'reels',
				  15, 'https://ig/r3', 9999, 999, 99, 999, '2026-07-07T03:00:00Z'),
				 ('r9', 'alpha', 'https://thumb/r9.jpg', '립틴트 릴스', '2026-07-05T03:00:00Z', 'reels',
				  18, 'https://ig/r9', 700, 70, 7, 400, '2026-07-08T03:00:00Z'),
				 ('f1', 'alpha', 'https://thumb/f1.jpg', '피드 조회수 없음', '2026-07-02T03:00:00Z', 'feed',
				  NULL, 'https://ig/f1', NULL, 50, 5, 300, '2026-07-05T03:00:00Z'),
				 ('f2', 'beta', 'https://thumb/f2.jpg', '피드 정렬용', '2026-07-03T03:00:00Z', 'feed',
				  NULL, 'https://ig/f2', 800, 80, 8, 350, '2026-07-06T03:00:00Z'),
				 ('nb1', 'alpha', 'https://thumb/nb1.jpg', '일상 브이로그', '2026-07-02T03:00:00Z', 'reels',
				  22, 'https://ig/nb1', 5000, 500, 50, 800, '2026-07-05T03:00:00Z')
				""");

		// 분석: r3만 없음(분석 미완 → 목록 제외). 유통사: r1=["다이소"], r2=[], r9=NULL.
		// nb1은 분석은 있으나 비뷰티(is_beauty=false) → 목록 제외.
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, main_category, sub_categories, ad_type,
				  detected_brands, detected_products, detected_distributors, is_beauty) VALUES
				 ('r1', 'makeup', '["아이라이너"]'::jsonb, 'organic',
				  '[{"name":"브랜드A"}]'::jsonb, '[{"name":"제품A"}]'::jsonb, '["다이소"]'::jsonb, true),
				 ('r2', 'skincare', '["토너"]'::jsonb, 'organic', NULL, NULL, '[]'::jsonb, true),
				 ('r9', 'makeup', '["립틴트"]'::jsonb, 'sponsored', NULL, NULL, NULL, true),
				 ('f1', 'makeup', '["립틴트"]'::jsonb, 'organic', NULL, NULL, NULL, true),
				 ('f2', 'skincare', '["토너"]'::jsonb, 'organic', NULL, NULL, NULL, true),
				 ('nb1', NULL, NULL, 'organic', NULL, NULL, NULL, false)
				""");
	}

	/** 기본 조회: 2026-07-01~07-10, contentType 기본(reels)·sort 기본(hype)·limit 기본(100). */
	private V1ContentQuery query() {
		return V1ContentQuery.of(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-10"),
				null, null, null, null, null, null, null, null, null, null);
	}

	private V1ContentQuery query(String contentType, String mainCategory, String midCategory,
			String subCategory, String distributorId, String sort, Integer limit) {
		return V1ContentQuery.of(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-10"),
				contentType, mainCategory, midCategory, subCategory, null, null, null,
				distributorId, sort, limit);
	}

	@Test
	void 비뷰티_콘텐츠는_랭킹에서_제외된다() {
		// nb1(is_beauty=false)은 필터 없으면 hype 800으로 목록(2위권)에 낄 텐데 빠진다
		List<ContentCardRow> rows = repository.findCards(query());

		assertThat(rows).extracting(ContentCardRow::shortCode).doesNotContain("nb1");
		assertThat(rows).extracting(ContentCardRow::shortCode).containsExactly("r2", "r1", "r9");
	}

	@Test
	void 분석_행_없는_콘텐츠는_제외된다() {
		List<ContentCardRow> rows = repository.findCards(query());

		// r3(분석 미완) 제외, 기본 hype 내림차순: r2(900) → r1(500) → r9(400)
		assertThat(rows).extracting(ContentCardRow::shortCode).containsExactly("r2", "r1", "r9");
	}

	@Test
	void 중분류는_어휘_테이블로_소분류_확장_매칭된다() {
		List<ContentCardRow> rows = repository.findCards(
				query(null, "makeup", "아이메이크업", null, null, null, null));

		// 아이메이크업 소속 소분류(아이라이너)만 — 립틴트(r9)는 립메이크업 소속이라 제외
		assertThat(rows).extracting(ContentCardRow::shortCode).containsExactly("r1");
	}

	@Test
	void 유통사_슬러그는_어휘_테이블로_이름_해석해_매칭된다() {
		List<ContentCardRow> rows = repository.findCards(
				query(null, null, null, null, "daiso", null, null));

		assertThat(rows).extracting(ContentCardRow::shortCode).containsExactly("r1");
	}

	@Test
	void 유통사_none은_미검출_행만_매칭된다() {
		List<ContentCardRow> rows = repository.findCards(
				query(null, null, null, null, "none", null, null));

		// r2(빈 배열)·r9(NULL) — 유통사 있는 r1 제외
		assertThat(rows).extracting(ContentCardRow::shortCode).containsExactlyInAnyOrder("r2", "r9");
	}

	@Test
	void views_정렬은_NULL이_마지막이다() {
		List<ContentCardRow> rows = repository.findCards(
				query("feed", null, null, null, null, "views", null));

		// f2(800) → f1(NULL, 피드 views 규약)
		assertThat(rows).extracting(ContentCardRow::shortCode).containsExactly("f2", "f1");
	}

	@Test
	void countCards는_limit과_무관하게_전체_건수다() {
		V1ContentQuery limited = query(null, null, null, null, null, null, 1);

		assertThat(repository.findCards(limited)).hasSize(1);
		assertThat(repository.countCards(limited)).isEqualTo(3);
	}

	@Test
	void 유통사_옵션은_전체를_슬러그_오름차순으로_내린다() {
		List<Map<String, Object>> options = repository.findDistributorOptions();

		assertThat(options).containsExactly(
				Map.of("id", "daiso", "name", "다이소"),
				Map.of("id", "oliveyoung", "name", "올리브영"));
	}
}
