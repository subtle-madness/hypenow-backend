package com.celfit.was.v1.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 6.3 리포트 조회 — 카테고리 맥락 라이브 집계(07-21 복구)의 모수·NULL 규칙 검증. */
class V1ContentReportRepositoryTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	V1ContentReportRepository repository;

	@BeforeEach
	void setUpTables() {
		jdbcTemplate.execute("DROP TABLE IF EXISTS content_analyses");
		jdbcTemplate.execute("DROP TABLE IF EXISTS contents");
		jdbcTemplate.execute("DROP TABLE IF EXISTS account_summaries");
		jdbcTemplate.execute("DROP TABLE IF EXISTS beauty_taxonomy");
		jdbcTemplate.execute("""
				CREATE TABLE contents (
				    short_code     text PRIMARY KEY,
				    account_handle text NOT NULL,
				    content_type   text,
				    views          bigint,
				    likes          bigint,
				    comments       bigint
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE content_analyses (
				    short_code                   text PRIMARY KEY,
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
				    comment_authenticity_grade   text,
				    comment_authenticity_note    text,
				    is_beauty                    boolean,
				    metric_timeliness            text
				)""");
		jdbcTemplate.execute("""
				CREATE TABLE account_summaries (handle text PRIMARY KEY, followers bigint)""");
		jdbcTemplate.execute("""
				CREATE TABLE beauty_taxonomy (
				    main_value text NOT NULL,
				    main_label text NOT NULL,
				    mid_label  text NOT NULL,
				    sub_label  text NOT NULL,
				    main_order int  NOT NULL,
				    mid_order  int  NOT NULL,
				    sub_order  int  NOT NULL,
				    axis       text NOT NULL DEFAULT 'beauty',
				    PRIMARY KEY (main_value, mid_label, sub_label)
				)""");
		jdbcTemplate.update("""
				INSERT INTO beauty_taxonomy (main_value, main_label, mid_label, sub_label,
				  main_order, mid_order, sub_order, axis) VALUES
				 ('makeup', '메이크업', '립메이크업', '립틴트', 3, 1, 1, 'beauty'),
				 ('makeup', '메이크업', '아이메이크업', '아이라이너', 3, 3, 1, 'beauty'),
				 ('skincare', '스킨케어', '크림', '크림', 1, 3, 1, 'beauty'),
				 ('snack', '간식류', '간식류', '과자', 11, 1, 1, 'fnb')""");
		jdbcTemplate.update("INSERT INTO account_summaries VALUES ('alpha', 10000)");

		// makeup 표본 후보 6건 + snack 1건(mnb) + skincare 1건. makeup 표본에 남는 건 m1·m2·m3·mlegacy 넷.
		jdbcTemplate.update("""
				INSERT INTO contents (short_code, account_handle, content_type, views, likes, comments) VALUES
				 ('m1',      'alpha', 'reels', 1000,  100, 10),
				 ('m2',      'alpha', 'reels', 3000,  300, 30),
				 ('m3',      'alpha', 'reels',  500,   50,  5),
				 ('mlegacy', 'alpha', 'reels',  800,   80,  8),
				 ('mfeed',   'alpha', 'feed',  NULL,   90,  9),
				 ('mlate',   'alpha', 'reels', 99999, 999, 99),
				 ('mnb',     'alpha', 'reels', 7000,  700, 70),
				 ('s1',      'alpha', 'reels', 2000,  200, 20)""");
		// mnb는 F&B 분석분(is_beauty=false ∧ snack) — makeup 표본에 안 섞이고 snack 표본이 된다
		jdbcTemplate.update("""
				INSERT INTO content_analyses (short_code, main_category, is_beauty, metric_timeliness,
				  ai_content_summary, recent_contents_count) VALUES
				 ('m1',      'makeup',   true,  'timely',        '요약', 12),
				 ('m2',      'makeup',   true,  'timely',        '요약', 12),
				 ('m3',      'makeup',   true,  'timely',        '요약', 12),
				 ('mlegacy', 'makeup',   true,  NULL,            '요약', 12),
				 ('mfeed',   'makeup',   true,  'timely',        '요약', 12),
				 ('mlate',   'makeup',   true,  'late_backfill', '요약', 12),
				 ('mnb',     'snack',    false, 'timely',        '요약', 12),
				 ('s1',      'skincare', true,  'timely',        '요약', 12)""");
	}

	@Test
	void 카테고리_표본은_같은_대분류의_뷰티_조회수보유_제때분만() {
		var ctx = repository.findCategoryContext("makeup", 1000L);

		// m1(1000)·m2(3000)·m3(500)·mlegacy(800) → 4건, 평균 1325.
		// 빠지는 것: mfeed(피드 views NULL) · mlate(늦크롤 백필) · mnb(다른 대분류·F&B) · s1(다른 대분류)
		assertThat(ctx.sampleSize()).isEqualTo(4L);
		assertThat(ctx.avgViews()).isEqualTo(1325L);
		assertThat(ctx.higherCount()).isEqualTo(1L); // 나(1000)보다 높은 건 m2뿐
	}

	@Test
	void 조회수_NULL_피드도_평균표본은_받는다() {
		var ctx = repository.findCategoryContext("makeup", null);

		assertThat(ctx.sampleSize()).isEqualTo(4L);
		assertThat(ctx.avgViews()).isEqualTo(1325L);
	}

	@Test
	void FnB_대분류도_같은_카테고리_표본을_받는다() {
		// mnb(snack, is_beauty=false, timely, views 7000)가 유일한 snack 표본 —
		// 구 쿼리의 is_beauty=true 게이트가 남아 있으면 0건으로 "현재 준비중" 화면이 된다.
		var ctx = repository.findCategoryContext("snack", 1000L);

		assertThat(ctx.sampleSize()).isEqualTo(1L);
		assertThat(ctx.avgViews()).isEqualTo(7000L);
		assertThat(ctx.higherCount()).isEqualTo(1L); // 나(1000)보다 높은 mnb
	}

	@Test
	void 표본이_없는_카테고리는_0건_평균_null() {
		var ctx = repository.findCategoryContext("haircare", 1000L);

		assertThat(ctx.sampleSize()).isEqualTo(0L);
		assertThat(ctx.avgViews()).isNull();
	}

	@Test
	void findReport는_지표시점과_카테고리_라벨을_함께_돌려준다() {
		var row = repository.findReport("mlate").orElseThrow();

		assertThat(row.metricTimeliness()).isEqualTo("late_backfill");
		assertThat(row.mainCategory()).isEqualTo("makeup");
		assertThat(row.categoryLabel()).isEqualTo("메이크업");
		// 프리즈 컬럼은 여전히 NULL — 라이브 집계가 대체한다
		assertThat(row.categoryTopPercentile()).isNull();
		assertThat(row.categorySampleSize()).isNull();
	}
}
