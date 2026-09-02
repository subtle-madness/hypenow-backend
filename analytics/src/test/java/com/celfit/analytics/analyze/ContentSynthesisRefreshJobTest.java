package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ContentSynthesisPort;
import com.celfit.analytics.llm.ContentToSynthesize;
import com.celfit.analytics.llm.Synthesis;
import com.celfit.analytics.testsupport.TestDb;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 해석 문구 갱신 계약: 낡은 행(synthesis_version 불일치)만 골라 문구·기준선만 UPDATE하고
 * 사실 추출 컬럼은 보존한다. 후보 자격과 무관하게 갱신되며, 갱신 후 재실행은 무동작.
 */
class ContentSynthesisRefreshJobTest {

	static final PostgreSQLContainer pg = TestDb.shared();

	JdbcTemplate db;
	DataSource ds;
	ContentSynthesisRefreshJob job;
	List<ContentToSynthesize> calls;

	ContentSynthesisPort fakePort() {
		return content -> {
			calls.add(content);
			return new Synthesis("새 요약: " + content.shortCode(), "새 비교 해설", "새 댓글 해석",
					"high", "새 판정 근거");
		};
	}

	void rewireJob(ContentSynthesisPort port) {
		job = new ContentSynthesisRefreshJob(db, ds, port, new AnalyticsSettings(db), ProgressReporter.NOOP);
	}

	@BeforeEach
	void setUp() {
		ds = TestDb.rawDataSource(pg);
		db = new JdbcTemplate(ds);
		calls = new ArrayList<>();
		TestDb.resetAndMigrate(db, ds);
		db.update("CREATE SCHEMA analytics");
		db.update("CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL)");
		// raw 대역 — 새 기준선(팔로워 분모 ER). old_c는 최근창 안(rank 있음), gone_c는 밖(계정 평균 폴백).
		db.update("""
				CREATE TABLE analytics.baseline_fixture (
				    short_code text PRIMARY KEY, recent_reels_avg_views numeric,
				    rank_in_recent_reels bigint, recent_reels_count bigint, recent_contents_count bigint,
				    recent12_avg_engagement_rate numeric, recent12_avg_like_count numeric,
				    recent12_avg_comment_count numeric, category_top_percentile smallint,
				    category_avg_views numeric, category_sample_size bigint)""");
		db.update("CREATE VIEW analytics.v_analysis_baseline AS SELECT * FROM analytics.baseline_fixture");
		db.update("""
				CREATE TABLE analytics.account_baseline_fixture (
				    account_handle text PRIMARY KEY, recent_reels_avg_views numeric,
				    recent_reels_count bigint, recent_contents_count bigint,
				    recent12_avg_engagement_rate numeric, recent12_avg_like_count numeric,
				    recent12_avg_comment_count numeric, category_top_percentile smallint,
				    category_avg_views numeric, category_sample_size bigint)""");
		db.update("""
				CREATE VIEW analytics.v_analysis_account_baseline AS
				SELECT * FROM analytics.account_baseline_fixture""");
		db.update("INSERT INTO analytics.baseline_fixture VALUES ('old_c', 9000, 2, 3, 3, 0.0812, 940, 61, 67, 19333, 3)");
		db.update("INSERT INTO analytics.account_baseline_fixture VALUES ('acct1', 8000, 3, 3, 0.0777, 900, 55, 70, 18000, 3)");

		db.update("""
				INSERT INTO contents (short_code, account_handle, caption, content_type, views, likes, comments)
				VALUES ('old_c', 'acct1', '캡션', 'reels', 11000, 520, 52)""");
		// gone_c: 미러(contents)에선 빠졌지만 계정 상세 시계열엔 남아 있다 — 계정 평균으로 갱신 가능.
		db.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at, content_type,
				  views, likes, comments, sponsored)
				VALUES ('gone_c', 'acct1', now() - interval '90 days', 'reels', 4000, 150, 15, false)""");
		// 낡은 행 2건 — old_c(후보 자격 있음)·gone_c(후보에서 빠져 전량 재분석으로는 못 고치던 것),
		// 그리고 이미 최신인 행 1건(fresh_c).
		db.update("""
				INSERT INTO content_analyses (short_code, model, ai_content_summary, contents_pattern,
				  ai_comment_insight, comment_authenticity_grade, comment_authenticity_note,
				  recent12_avg_engagement_rate, main_category, ad_type, detected_brands, is_beauty,
				  metric_timeliness, synthesis_version) VALUES
				  ('old_c','old-model','옛 요약','옛 패턴','옛 댓글','normal','옛 근거',
				   0.0496,'cleansing','sponsored','[{"name":"브랜드A"}]'::jsonb,true,'timely',NULL),
				  ('gone_c','old-model','옛 요약2','옛 패턴2','옛 댓글2','normal','옛 근거2',
				   0.0311,'makeup','organic','[{"name":"브랜드B"}]'::jsonb,true,'late_backfill',NULL),
				  ('orphan_c','old-model','옛 요약3','옛 패턴3','옛 댓글3','normal','옛 근거3',
				   0.0222,'makeup','organic','[]'::jsonb,true,'timely',NULL),
				  ('fresh_c','new-model','최신 요약','최신 패턴','최신 댓글','high','최신 근거',
				   0.0800,'haircare','organic','[]'::jsonb,true,'timely',%d)"""
				.formatted(Synthesis.VERSION));
		rewireJob(fakePort());
	}

	@Test
	void 낡은_행만_갱신하고_최신_행은_건드리지_않는다() {
		int processed = job.run().processed();

		assertEquals(2, processed); // old_c·gone_c — orphan_c는 앵커 없어 보존
		assertEquals("새 요약: old_c", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code='old_c'", String.class));
		assertEquals("최신 요약", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code='fresh_c'", String.class));
		// orphan_c는 앵커가 없어 손대지 않는다 — 낡은 채 남지만 빈 근거로 나빠지진 않는다
		assertEquals("옛 요약3", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code='orphan_c'", String.class));
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE synthesis_version IS NULL", Long.class));
	}

	/** 앵커를 못 구하는 행은 재생성하면 "표본 부족" 문구로 나빠지므로 보존한다. */
	@Test
	void 기준선_앵커가_없으면_기존_문구를_보존한다() {
		job.run();

		assertTrue(calls.stream().noneMatch(c -> c.shortCode().equals("orphan_c")));
		assertEquals("옛 패턴3", db.queryForObject(
				"SELECT contents_pattern FROM content_analyses WHERE short_code='orphan_c'", String.class));
	}

	/** 이 설계의 핵심 — 사실 추출은 재생성 비용을 치르지 않고 그대로 남는다. */
	@Test
	void 사실_추출_컬럼은_보존된다() {
		job.run();

		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code='old_c'", String.class));
		assertEquals("sponsored", db.queryForObject(
				"SELECT ad_type FROM content_analyses WHERE short_code='old_c'", String.class));
		assertEquals("브랜드A", db.queryForObject(
				"SELECT detected_brands->0->>'name' FROM content_analyses WHERE short_code='old_c'", String.class));
		// 지표 수집 시점 사실도 갱신 대상이 아니다
		assertEquals("late_backfill", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code='gone_c'", String.class));
	}

	/** 문구가 인용하는 기준선 스냅샷도 새 정의로 함께 갱신돼야 한다(동결의 의미 유지). */
	@Test
	void 기준선_스냅샷도_새_정의로_갱신된다() {
		job.run();

		assertEquals(0, new java.math.BigDecimal("0.0812").compareTo(db.queryForObject(
				"SELECT recent12_avg_engagement_rate FROM content_analyses WHERE short_code='old_c'",
				java.math.BigDecimal.class)));
		assertEquals(2, db.queryForObject(
				"SELECT rank_in_recent_reels FROM content_analyses WHERE short_code='old_c'", Integer.class));
		assertNotNull(db.queryForObject(
				"SELECT synthesized_at FROM content_analyses WHERE short_code='old_c'", java.time.OffsetDateTime.class));
	}

	/** 후보에서 빠진 콘텐츠(전량 재분석으로는 손댈 수 없던 것)도 계정 평균 앵커로 갱신된다. */
	@Test
	void 후보_자격이_없는_행도_계정_평균으로_갱신된다() {
		job.run();

		assertEquals("새 요약: gone_c", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code='gone_c'", String.class));
		ContentToSynthesize call = calls.stream()
				.filter(c -> c.shortCode().equals("gone_c")).findFirst().orElseThrow();
		assertEquals(8000L, call.baseline().get("recent_reels_avg_views")); // 계정 평균 앵커
		assertNull(call.baseline().get("rank_in_recent_reels")); // 최근창 밖이라 rank 없음
	}

	/** 저장된 사실이 프롬프트에 "확인된 사실"로 실려 LLM이 재판정하지 않게 한다. */
	@Test
	void 저장된_사실이_프롬프트_입력으로_전달된다() {
		job.run();

		ContentToSynthesize call = calls.stream()
				.filter(c -> c.shortCode().equals("old_c")).findFirst().orElseThrow();
		assertEquals("cleansing", call.facts().get("main_category"));
		assertEquals("sponsored", call.facts().get("ad_type"));
		assertTrue(call.facts().get("detected_brands").toString().contains("브랜드A"));
	}

	@Test
	void 갱신_후_재실행은_대상이_없다() {
		job.run();
		calls.clear();

		assertEquals(0, job.run().processed());
		assertTrue(calls.stream().noneMatch(c -> c.shortCode().equals("old_c")));
	}

	@Test
	void 빈_문구는_저장하지_않고_다른_행은_처리된다() {
		rewireJob(content -> {
			calls.add(content);
			if (content.shortCode().equals("old_c")) {
				return new Synthesis("", "패턴", "댓글", "normal", "근거");
			}
			return new Synthesis("새 요약", "패턴", "댓글", "normal", "근거");
		});

		assertEquals(1, job.run().processed()); // gone_c만 성공 (old_c 빈 문구 실패, orphan_c 보존)
		assertEquals("옛 요약", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code='old_c'", String.class));
	}
}
