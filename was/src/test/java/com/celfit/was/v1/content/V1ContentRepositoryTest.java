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
				    is_beauty             boolean,
				    metric_timeliness     text
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
		// hype_score_precise는 기존 테스트의 정렬 기대를 그대로 보존하도록 hype_score와 같은 값을
		// 소수로 실었다(2026-07-30 정렬 키 전환 — hype_score DESC → hype_score_precise DESC).
		jdbcTemplate.update("""
				INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, posted_at, content_type,
				  video_duration, original_url, views, likes, comments, hype_score, metric_captured_at,
				  hype_score_precise) VALUES
				 ('r1', 'alpha', 'https://thumb/r1.jpg', '아이라이너 릴스', '2026-07-02T03:00:00Z', 'reels',
				  20, 'https://ig/r1', 1000, 100, 10, 500, '2026-07-05T03:00:00Z', 500),
				 ('r2', 'beta', 'https://thumb/r2.jpg', '토너 릴스', '2026-07-03T03:00:00Z', 'reels',
				  25, 'https://ig/r2', 3000, 300, 30, 900, '2026-07-06T03:00:00Z', 900),
				 ('r3', 'alpha', 'https://thumb/r3.jpg', '분석 미완 릴스', '2026-07-04T03:00:00Z', 'reels',
				  15, 'https://ig/r3', 9999, 999, 99, 999, '2026-07-07T03:00:00Z', 999),
				 ('r9', 'alpha', 'https://thumb/r9.jpg', '립틴트 릴스', '2026-07-05T03:00:00Z', 'reels',
				  18, 'https://ig/r9', 700, 70, 7, 400, '2026-07-08T03:00:00Z', 400),
				 ('f1', 'alpha', 'https://thumb/f1.jpg', '피드 조회수 없음', '2026-07-02T03:00:00Z', 'feed',
				  NULL, 'https://ig/f1', NULL, 50, 5, 300, '2026-07-05T03:00:00Z', 300),
				 ('f2', 'beta', 'https://thumb/f2.jpg', '피드 정렬용', '2026-07-03T03:00:00Z', 'feed',
				  NULL, 'https://ig/f2', 800, 80, 8, 350, '2026-07-06T03:00:00Z', 350),
				 ('nb1', 'alpha', 'https://thumb/nb1.jpg', '일상 브이로그', '2026-07-02T03:00:00Z', 'reels',
				  22, 'https://ig/nb1', 5000, 500, 50, 800, '2026-07-05T03:00:00Z', 800),
				 -- 시점 마킹 대조군(별도 기간 [07-11, 07-20) — 기존 테스트 무영향):
				 -- tl1=timely·lb1=late_backfill·lg1=시점 NULL(레거시). 셋 다 뷰티 릴스.
				 ('tl1', 'alpha', 'https://thumb/tl1.jpg', '제때 릴스', '2026-07-15T03:00:00Z', 'reels',
				  20, 'https://ig/tl1', 1000, 100, 10, 600, '2026-07-18T03:00:00Z', 600),
				 ('lb1', 'alpha', 'https://thumb/lb1.jpg', '늦크롤 백필 릴스', '2026-07-16T03:00:00Z', 'reels',
				  20, 'https://ig/lb1', 9999, 999, 99, 999, '2026-07-25T03:00:00Z', 999),
				 ('lg1', 'alpha', 'https://thumb/lg1.jpg', '레거시 미분류 릴스', '2026-07-17T03:00:00Z', 'reels',
				  20, 'https://ig/lg1', 500, 50, 5, 300, '2026-07-20T03:00:00Z', 300),
				 -- 소수점 정렬 회귀 대조군(별도 기간 [08-01, 08-05) — 기존 테스트 무영향): z1·a1은
				 -- hype_score(정수)가 700으로 동일(동점)하지만 hype_score_precise가 다르다. short_code는
				 -- 알파벳순(a1 < z1)이 precise 순서(z1 > a1)와 정반대가 되도록 골랐다 — 구코드
				 -- (ORDER BY c.hype_score DESC, short_code)였다면 동점이라 a1이 먼저 나왔을 것.
				 ('z1', 'alpha', 'https://thumb/z1.jpg', '정밀도 상위 릴스', '2026-08-02T03:00:00Z', 'reels',
				  20, 'https://ig/z1', 1000, 100, 10, 700, '2026-08-05T03:00:00Z', 65.4321),
				 ('a1', 'alpha', 'https://thumb/a1.jpg', '정밀도 하위 릴스', '2026-08-02T03:00:00Z', 'reels',
				  20, 'https://ig/a1', 1000, 100, 10, 700, '2026-08-05T03:00:00Z', 65.1234)
				""");

		// 분석: r3만 없음(분석 미완 → 목록 제외). 유통사: r1=["다이소"], r2=[], r9=NULL.
		// nb1은 분석은 있으나 비뷰티(is_beauty=false) → 목록 제외.
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, main_category, sub_categories, ad_type,
				  detected_brands, detected_products, detected_distributors, is_beauty, metric_timeliness) VALUES
				 ('r1', 'makeup', '["아이라이너"]'::jsonb, 'organic',
				  '[{"name":"브랜드A"}]'::jsonb, '[{"name":"제품A"}]'::jsonb, '["다이소"]'::jsonb, true, 'timely'),
				 ('r2', 'skincare', '["토너"]'::jsonb, 'organic', NULL, NULL, '[]'::jsonb, true, 'timely'),
				 ('r9', 'makeup', '["립틴트"]'::jsonb, 'sponsored', NULL, NULL, NULL, true, 'timely'),
				 ('f1', 'makeup', '["립틴트"]'::jsonb, 'organic', NULL, NULL, NULL, true, 'timely'),
				 ('f2', 'skincare', '["토너"]'::jsonb, 'organic', NULL, NULL, NULL, true, 'timely'),
				 ('nb1', NULL, NULL, 'organic', NULL, NULL, NULL, false, 'timely'),
				 ('tl1', 'makeup', '["아이라이너"]'::jsonb, 'organic', NULL, NULL, NULL, true, 'timely'),
				 ('lb1', 'makeup', '["아이라이너"]'::jsonb, 'organic', NULL, NULL, NULL, true, 'late_backfill'),
				 ('lg1', 'makeup', '["아이라이너"]'::jsonb, 'organic', NULL, NULL, NULL, true, NULL),
				 ('z1', 'makeup', '["아이라이너"]'::jsonb, 'organic', NULL, NULL, NULL, true, 'timely'),
				 ('a1', 'makeup', '["아이라이너"]'::jsonb, 'organic', NULL, NULL, NULL, true, 'timely')
				""");
	}

	/** 기본 조회: 2026-07-01~07-10, contentType 기본(reels)·sort 기본(hype)·limit 기본(100)·offset 기본(0). */
	private V1ContentQuery query() {
		return V1ContentQuery.of(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-10"),
				null, null, null, null, null, null, null, null, null, null, null);
	}

	private V1ContentQuery query(String contentType, String mainCategory, String midCategory,
			String subCategory, String distributorId, String sort, Integer limit) {
		return query(contentType, mainCategory, midCategory, subCategory, distributorId, sort, limit, null);
	}

	private V1ContentQuery query(String contentType, String mainCategory, String midCategory,
			String subCategory, String distributorId, String sort, Integer limit, Integer offset) {
		return V1ContentQuery.of(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-10"),
				contentType, mainCategory, midCategory, subCategory, null, null, null,
				distributorId, sort, limit, offset);
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
	void late_backfill은_랭킹에서_제외되고_timely와_레거시NULL은_노출된다() {
		// [07-11, 07-20) 창: tl1(timely)·lg1(시점 NULL 레거시)는 노출, lb1(late_backfill)은 제외.
		// lb1은 hype 999로 필터 없으면 1위인데, 늦크롤 지표 편향 때문에 랭킹에서 아예 빠진다.
		V1ContentQuery q = V1ContentQuery.of(LocalDate.parse("2026-07-11"), LocalDate.parse("2026-07-20"),
				null, null, null, null, null, null, null, null, null, null, null);

		List<ContentCardRow> rows = repository.findCards(q);

		assertThat(rows).extracting(ContentCardRow::shortCode).doesNotContain("lb1");
		// 기본 hype 내림차순: tl1(600) → lg1(300)
		assertThat(rows).extracting(ContentCardRow::shortCode).containsExactly("tl1", "lg1");
		assertThat(repository.countCards(q)).isEqualTo(2);
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
	void offset은_다음_페이지로_건너뛰고_countCards는_영향받지_않는다() {
		// 기본 hype 내림차순: r2(900) → r1(500) → r9(400) — limit=1로 한 건씩 페이지네이션
		V1ContentQuery page1 = query(null, null, null, null, null, null, 1, 0);
		V1ContentQuery page2 = query(null, null, null, null, null, null, 1, 1);

		assertThat(repository.findCards(page1)).extracting(ContentCardRow::shortCode).containsExactly("r2");
		assertThat(repository.findCards(page2)).extracting(ContentCardRow::shortCode).containsExactly("r1");
		assertThat(repository.countCards(page2)).isEqualTo(3);
	}

	@Test
	void 아카이브된_이미지는_img_경로로_미아카이브는_원본_URL로_서빙된다() {
		// r1(alpha)만 아카이브: 썸네일 kind='thumbnail'/key=short_code, 프로필 kind='profile'/key=handle
		jdbcTemplate.update("INSERT INTO image_assets(kind, key, object_path, source_name) "
				+ "VALUES ('thumbnail', 'r1', 'thumb/r1.jpg', 'r1.jpg')");
		jdbcTemplate.update("INSERT INTO image_assets(kind, key, object_path, source_name) "
				+ "VALUES ('profile', 'alpha', 'profile/alpha.jpg', 'alpha.jpg')");

		List<ContentCardRow> rows = repository.findCards(query());

		ContentCardRow archived = rows.stream().filter(r -> r.shortCode().equals("r1")).findFirst().orElseThrow();
		assertThat(archived.thumbnailUrl()).isEqualTo("/img/thumb/r1.jpg");
		assertThat(archived.profileImageUrl()).isEqualTo("/img/profile/alpha.jpg");

		// r2(beta)는 미아카이브 — 원본 CDN URL 그대로 폴백
		ContentCardRow fallback = rows.stream().filter(r -> r.shortCode().equals("r2")).findFirst().orElseThrow();
		assertThat(fallback.thumbnailUrl()).isEqualTo("https://thumb/r2.jpg");
		assertThat(fallback.profileImageUrl()).isEqualTo("https://pic/beta.jpg");
	}

	@Test
	void hype_정렬은_정수가_같아도_소수_정밀도로_순서를_가른다() {
		// z1·a1은 hype_score(정수) 700으로 동점이지만 hype_score_precise가 다르다(z1=65.4321 >
		// a1=65.1234). short_code는 알파벳순(a1 < z1)이 precise 순서와 정반대라 — 구코드
		// (ORDER BY c.hype_score DESC NULLS LAST, short_code)로 되돌리면 동점 타이브레이크가
		// short_code를 타서 a1이 먼저 나온다(직접 확인: orderBy()를 hype_score 기준으로 바꿔
		// 재실행하면 이 단언이 깨진다).
		V1ContentQuery q = V1ContentQuery.of(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-05"),
				null, null, null, null, null, null, null, null, null, null, null);

		List<ContentCardRow> rows = repository.findCards(q);

		assertThat(rows).extracting(ContentCardRow::shortCode).containsExactly("z1", "a1");
		assertThat(rows.get(0).hypeScorePrecise()).isEqualByComparingTo("65.4321");
		assertThat(rows.get(1).hypeScorePrecise()).isEqualByComparingTo("65.1234");
	}

	@Test
	void 유통사_옵션은_전체를_슬러그_오름차순으로_내린다() {
		List<Map<String, Object>> options = repository.findDistributorOptions();

		assertThat(options).containsExactly(
				Map.of("id", "daiso", "name", "다이소"),
				Map.of("id", "oliveyoung", "name", "올리브영"));
	}
}
