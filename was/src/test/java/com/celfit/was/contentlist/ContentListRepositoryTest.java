package com.celfit.was.contentlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ContentListRepositoryTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	ContentListRepository repository;

	@BeforeEach
	void setUpTables() {
		// PostDetailRepositoryTest와 동일 DDL(accounts·contents·content_analyses·content_metric_snapshots) —
		// content_comments는 목록 조회에 불필요해 제외.
		jdbcTemplate.execute("DROP TABLE IF EXISTS content_metric_snapshots");
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
				    short_code     text PRIMARY KEY,
				    account_handle text NOT NULL,
				    thumbnail_url  text,
				    caption        text,
				    posted_at      timestamptz,
				    content_type   text,
				    video_duration numeric,
				    original_url   text,
				    views          bigint,
				    likes          bigint,
				    comments       bigint,
				    hype_score     bigint
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE content_analyses (
				    short_code                   text PRIMARY KEY,
				    analyzed_at                  timestamptz NOT NULL DEFAULT now(),
				    model                        text NOT NULL,
				    ai_content_summary           text,
				    contents_pattern             text,
				    ai_comment_insight           text,
				    recent_reels_avg_views       bigint,
				    rank_in_recent_reels         smallint,
				    recent_reels_count           smallint,
				    recent_contents_count        smallint,
				    recent12_avg_engagement_rate numeric,
				    recent12_avg_like_count      bigint,
				    recent12_avg_comment_count   bigint,
				    category_top_percentile      smallint,
				    category_avg_views           bigint,
				    category_sample_size         bigint,
				    detected_brands              jsonb,
				    sponsored_signal_level       text,
				    sponsored_signal_reasons     jsonb,
				    ad_disclosure                text,
				    detected_product_categories  jsonb,
				    vlm_attributes               jsonb,
				    main_category                text,
				    sub_categories               jsonb,
				    ad_type                      text,
				    comment_authenticity_grade   text,
				    comment_authenticity_note    text
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE content_metric_snapshots (
				    id          bigint PRIMARY KEY,
				    short_code  text NOT NULL,
				    captured_at timestamptz NOT NULL,
				    views       bigint,
				    likes       bigint,
				    comments    bigint,
				    hype_score  bigint
				)""");

		// 계정 2: alpha(5000) · beta(20000)
		jdbcTemplate.update("""
				INSERT INTO accounts (handle, display_name, profile_image_url, followers) VALUES
				 ('alpha', '알파', 'https://pic/alpha.jpg', 5000),
				 ('beta', '베타', 'https://pic/beta.jpg', 20000)
				""");

		// 콘텐츠 6 — posted_at은 KST 정오 = UTC 03:00. h2 캡션에만 키워드 필터용 문자열("토너캡션") 포함.
		jdbcTemplate.update("""
				INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, posted_at, content_type) VALUES
				 ('h1', 'alpha', 'https://thumb/h1.jpg', '여름 립케어 루틴', '2026-06-28T03:00:00Z', 'reels'),
				 ('h2', 'beta',  'https://thumb/h2.jpg', '여름 토너캡션 루틴', '2026-07-01T03:00:00Z', 'reels'),
				 ('h3', 'alpha', 'https://thumb/h3.jpg', '데일리 피드 룩', '2026-07-02T03:00:00Z', 'feed'),
				 ('h4', 'beta',  'https://thumb/h4.jpg', '분석 미완 콘텐츠', '2026-07-03T03:00:00Z', 'reels'),
				 ('h5', 'alpha', 'https://thumb/h5.jpg', '스냅샷 시점 부재', '2026-07-02T03:00:00Z', 'reels'),
				 ('h6', 'alpha', 'https://thumb/h6.jpg', '기간 밖 콘텐츠', '2026-06-20T03:00:00Z', 'reels')
				""");

		// 분석: h1(makeup/스폰서드/브랜드2), h2(skincare/organic), h3(VLM 전부 NULL), h4는 분석 없음(제외),
		// h5·h6는 분석은 있으나 다른 사유로 제외.
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, model, main_category, sub_categories, ad_type,
				  detected_product_categories, detected_brands) VALUES
				 ('h1', 'test-model', 'makeup', '["립메이크업","립틴트"]'::jsonb, 'sponsored',
				  '["립틴트"]'::jsonb, '[{"name":"brandA"},{"name":"brandB"}]'::jsonb),
				 ('h2', 'test-model', 'skincare', '["스킨/토너","토너"]'::jsonb, 'organic', NULL, NULL),
				 ('h3', 'test-model', NULL, NULL, NULL, NULL, NULL),
				 ('h5', 'test-model', NULL, NULL, NULL, NULL, NULL),
				 ('h6', 'test-model', NULL, NULL, NULL, NULL, NULL)
				""");

		// 스냅샷: cutoff(기간 끝 2026-07-03 → cutoff=2026-07-03T15:00:00Z) 이전 최신 1행이 as-of 값.
		// h5는 cutoff 이전 스냅샷이 없어(07-05만 존재) 시점 부재로 제외.
		jdbcTemplate.update("""
				INSERT INTO content_metric_snapshots (id, short_code, captured_at, views, likes, comments, hype_score) VALUES
				 (101, 'h1', '2026-07-01T03:00:00Z', 1000, 100, 10, 1000),
				 (102, 'h2', '2026-07-03T03:00:00Z', 5000, 50, 5, 5000),
				 (103, 'h3', '2026-07-03T03:00:00Z', NULL, 300, 30, 330),
				 (104, 'h4', '2026-07-03T03:00:00Z', 9999999, 100, 10, 9999999),
				 (105, 'h5', '2026-07-05T03:00:00Z', 7000, 70, 7, 7000),
				 (106, 'h6', '2026-06-21T03:00:00Z', 200, 20, 2, 200)
				""");
	}

	/** 기본 조회: start=2026-06-27, end=2026-07-03, 필터 없음, sort=hype. */
	private ContentListQuery query() {
		return ContentListQuery.of(LocalDate.parse("2026-06-27"), LocalDate.parse("2026-07-03"),
				null, null, null, null, null, null, null, null, "hype");
	}

	private ContentListQuery queryWithSort(String sort) {
		return ContentListQuery.of(LocalDate.parse("2026-06-27"), LocalDate.parse("2026-07-03"),
				null, null, null, null, null, null, null, null, sort);
	}

	@Test
	void 기간_분석완료_시점스냅샷_교집합만_나온다() {
		List<ContentListRow> rows = repository.findContents(query());

		assertThat(rows).extracting(ContentListRow::shortCode).containsExactly("h2", "h1", "h3");
		assertThat(repository.countContents(query())).isEqualTo(3);
	}

	@Test
	void hype_정렬은_스냅샷_hype_score_내림차순이다() {
		List<ContentListRow> rows = repository.findContents(queryWithSort("hype"));

		assertThat(rows).extracting(ContentListRow::shortCode).containsExactly("h2", "h1", "h3");
		assertThat(rows).extracting(ContentListRow::hypeScore).containsExactly(5000L, 1000L, 330L);
	}

	@Test
	void latest_정렬은_게시일_내림차순이다() {
		List<ContentListRow> rows = repository.findContents(queryWithSort("latest"));

		assertThat(rows).extracting(ContentListRow::shortCode).containsExactly("h3", "h2", "h1");
	}

	@Test
	void engagement_정렬은_스냅샷_ER_내림차순_null_last다() {
		// h1=(100+10)/1000=0.1100, h2=(50+5)/5000=0.0110, h3=views NULL → ER null(마지막)
		List<ContentListRow> rows = repository.findContents(queryWithSort("engagement"));

		assertThat(rows).extracting(ContentListRow::shortCode).containsExactly("h1", "h2", "h3");
	}

	@Test
	void 지표는_cutoff_이전_최신_스냅샷_값이다() {
		List<ContentListRow> rows = repository.findContents(query());

		ContentListRow h2 = rows.stream()
				.filter(r -> r.shortCode().equals("h2"))
				.findFirst()
				.orElseThrow();
		assertThat(h2.views()).isEqualTo(5000L);
		assertThat(h2.likes()).isEqualTo(50L);
		assertThat(h2.comments()).isEqualTo(5L);
		assertThat(h2.hypeScore()).isEqualTo(5000L);
		assertThat(h2.capturedAt()).isEqualTo(OffsetDateTime.parse("2026-07-03T03:00:00Z"));
	}

	@Test
	void follower_구간_필터() {
		ContentListQuery q = ContentListQuery.of(LocalDate.parse("2026-06-27"), LocalDate.parse("2026-07-03"),
				null, null, null, null, "3k-10k", null, null, null, "hype");

		List<ContentListRow> rows = repository.findContents(q);
		assertThat(rows).extracting(ContentListRow::shortCode).containsExactlyInAnyOrder("h1", "h3");
		assertThat(repository.countContents(q)).isEqualTo(2);
	}

	@Test
	void 미지원_follower_값은_매칭_0이다() {
		// FollowerRange.UNKNOWN([0,0) 공집합) — 필터 무시(전체 매칭)가 아니라 매칭 0이어야 한다(verbatim 계약)
		ContentListQuery q = ContentListQuery.of(LocalDate.parse("2026-06-27"), LocalDate.parse("2026-07-03"),
				null, null, null, null, "bogus", null, null, null, "hype");
		assertThat(repository.findContents(q)).isEmpty();
		assertThat(repository.countContents(q)).isZero();
	}

	@Test
	void content_type_필터() {
		ContentListQuery q = ContentListQuery.of(LocalDate.parse("2026-06-27"), LocalDate.parse("2026-07-03"),
				null, null, null, "feed", null, null, null, null, "hype");

		List<ContentListRow> rows = repository.findContents(q);
		assertThat(rows).extracting(ContentListRow::shortCode).containsExactly("h3");
	}

	@Test
	void 캡션_키워드_필터() {
		ContentListQuery q = ContentListQuery.of(LocalDate.parse("2026-06-27"), LocalDate.parse("2026-07-03"),
				null, null, null, null, null, null, null, "토너캡션", "hype");

		List<ContentListRow> rows = repository.findContents(q);
		assertThat(rows).extracting(ContentListRow::shortCode).containsExactly("h2");
	}

	@Test
	void ad_type_필터는_미분류를_제외한다() {
		ContentListQuery sponsored = ContentListQuery.of(LocalDate.parse("2026-06-27"), LocalDate.parse("2026-07-03"),
				null, null, null, null, null, "sponsored", null, null, "hype");
		ContentListQuery organic = ContentListQuery.of(LocalDate.parse("2026-06-27"), LocalDate.parse("2026-07-03"),
				null, null, null, null, null, "organic", null, null, "hype");

		assertThat(repository.findContents(sponsored)).extracting(ContentListRow::shortCode)
				.containsExactly("h1");
		assertThat(repository.findContents(organic)).extracting(ContentListRow::shortCode)
				.containsExactly("h2");
	}

	@Test
	void 카테고리_필터는_verbatim_매칭이다() {
		ContentListQuery mainCategory = ContentListQuery.of(
				LocalDate.parse("2026-06-27"), LocalDate.parse("2026-07-03"),
				"makeup", null, null, null, null, null, null, null, "hype");
		ContentListQuery midCategory = ContentListQuery.of(
				LocalDate.parse("2026-06-27"), LocalDate.parse("2026-07-03"),
				null, "립메이크업", null, null, null, null, null, null, "hype");
		ContentListQuery subCategory = ContentListQuery.of(
				LocalDate.parse("2026-06-27"), LocalDate.parse("2026-07-03"),
				null, null, "립틴트", null, null, null, null, null, "hype");

		assertThat(repository.findContents(mainCategory)).extracting(ContentListRow::shortCode)
				.containsExactly("h1");
		assertThat(repository.findContents(midCategory)).extracting(ContentListRow::shortCode)
				.containsExactly("h1");
		assertThat(repository.findContents(subCategory)).extracting(ContentListRow::shortCode)
				.containsExactly("h1");
	}

	@Test
	void distributor_지정_시_빈_목록이다() {
		// 유통사 저장 컬럼 부재 — 지정 시 매칭 0(계약 고정). 컬럼 신설 후 이 테스트를 갱신한다.
		ContentListQuery q = ContentListQuery.of(LocalDate.parse("2026-06-27"), LocalDate.parse("2026-07-03"),
				null, null, null, null, null, null, "올리브영", null, "hype");

		assertThat(repository.findContents(q)).isEmpty();
		assertThat(repository.countContents(q)).isEqualTo(0);
	}

	@Test
	void 테이블_부재_시_빈_값으로_저하한다() {
		jdbcTemplate.execute("DROP TABLE content_metric_snapshots");
		jdbcTemplate.execute("DROP TABLE content_analyses");
		jdbcTemplate.execute("DROP TABLE contents");
		jdbcTemplate.execute("DROP TABLE accounts");

		assertThat(repository.findContents(query())).isEmpty();
		assertThat(repository.countContents(query())).isEqualTo(0);
	}
}
