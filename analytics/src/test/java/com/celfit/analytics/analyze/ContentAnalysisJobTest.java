package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.Synthesis;
import com.celfit.analytics.llm.SynthesisPort;
import com.celfit.analytics.llm.VisionPort;
import com.celfit.analytics.llm.VlmResult;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 콘텐츠 분석 배치 계약 5케이스 (계획 Task 3):
 * ① 미분석+분류완료 저장 ② 이미 분석 스킵 ③ 댓글 있는데 미분류 제외 ④ vlm-enabled=false → VisionPort 미호출·NULL
 * ⑤ 한 콘텐츠 실패 격리. 골격은 CommentClassificationJobTest 패턴(Testcontainers 1개 + raw 대역 뷰) 재사용.
 */
@Testcontainers
class ContentAnalysisJobTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	JdbcTemplate db;
	DataSource ds;
	ContentAnalysisJob job;
	List<ContentToAnalyze> synthesisCalls;
	List<String> visionCalls;

	/** fake SynthesisPort: 호출 기록 + 고정 응답. */
	SynthesisPort fakeSynthesisPort() {
		return content -> {
			synthesisCalls.add(content);
			return new Synthesis("요약: " + content.shortCode(), "패턴 해석", "댓글 인사이트", "high", "판정 근거");
		};
	}

	/** fake VisionPort: 호출 기록 + 고정 응답 (사용되면 안 되는 케이스에서 호출 여부 검증용). */
	VisionPort fakeVisionPort() {
		return (thumbnailUrl, caption) -> {
			visionCalls.add(thumbnailUrl);
			return new VlmResult(List.of(new VlmResult.Brand("브랜드A", "화면 노출")), "high",
					List.of("협찬 표기 있음"), "표기 있음", List.of("클렌징"),
					List.of(new VlmResult.Attribute("무드", "화사함")), "skincare", List.of("클렌징폼"), "sponsored");
		};
	}

	void rewireJob(SynthesisPort synthesisPort, boolean vlmEnabled) {
		job = new ContentAnalysisJob(db, ds, synthesisPort, fakeVisionPort(), new AnalyticsSettings(db), vlmEnabled);
	}

	@BeforeEach
	void setUp() {
		ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		synthesisCalls = new ArrayList<>();
		visionCalls = new ArrayList<>();
		db.update("DROP SCHEMA IF EXISTS analytics CASCADE");
		// 테스트 간 완전 초기화 (B2 패턴 — content_analyses 포함)
		db.update("DROP TABLE IF EXISTS content_analyses, comment_classifications, accounts, contents, content_comments, content_metric_snapshots");
		db.update("DROP TABLE IF EXISTS app_setting, flyway_schema_history");
		Flyway.configure().dataSource(ds).locations("classpath:db/migration/analysis")
				.baselineOnMigrate(true).baselineVersion("0").load().migrate();

		// raw 대역: analytics.v_analysis_baseline과 같은 컬럼의 뷰 (고정 수치 테이블 기반).
		// 실제 뷰의 컬럼 타입(numeric/bigint/smallint 혼재)을 그대로 재현해 JDBC 매퍼(getBigDecimal)를 검증한다.
		db.update("CREATE SCHEMA analytics");
		db.update("CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL)");
		db.update("""
				CREATE TABLE analytics.baseline_fixture (
				    short_code                   text PRIMARY KEY,
				    recent_reels_avg_views       numeric,
				    rank_in_recent_reels         bigint,
				    recent_reels_count           bigint,
				    recent_contents_count        bigint,
				    recent12_avg_engagement_rate numeric,
				    recent12_avg_like_count      numeric,
				    recent12_avg_comment_count   numeric,
				    category_top_percentile      smallint,
				    category_avg_views           numeric,
				    category_sample_size         bigint
				)""");
		db.update("""
				CREATE VIEW analytics.v_analysis_baseline AS SELECT * FROM analytics.baseline_fixture""");
		db.update("""
				INSERT INTO analytics.baseline_fixture VALUES
				  ('post_a', 9000, 1, 2, 3, 0.0496, 940, 61, 67, 19333, 3),
				  ('post_b', NULL, NULL, 0, 3, 0.03, 500, 40, 90, 15000, 3),
				  ('post_c', 9000, 2, 2, 3, 0.04, 700, 50, 80, 19333, 3)""");

		// 분석 DB 시드: contents(미러) 3행 + content_comments·comment_classifications로 대상 조건 구성.
		// post_a: 댓글 있고 분류 완료 (대상 O), post_b: 댓글 없음 (대상 O), post_c: 댓글 있고 미분류 (대상 X)
		db.update("""
				INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, content_type, views, likes, comments) VALUES
				  ('post_a', 'acct1', 'https://img/a.jpg', '캡션A', 'reels', 11000, 520, 52),
				  ('post_b', 'acct1', 'https://img/b.jpg', '캡션B', 'feed', NULL, 2000, 100),
				  ('post_c', 'acct1', 'https://img/c.jpg', '캡션C', 'reels', 7000, 300, 30)""");
		db.update("""
				INSERT INTO content_comments (id, short_code, author_masked, body, like_count) VALUES
				  (1, 'post_a', 'aaa***', '어디서 사요?', 3),
				  (2, 'post_a', 'bbb***', '예뻐요', 1),
				  (3, 'post_c', 'ccc***', '좋아요', 0)""");
		db.update("""
				INSERT INTO comment_classifications (id, short_code, ai_category, model) VALUES
				  (1, 'post_a', 'purchase', 'claude-test'),
				  (2, 'post_a', 'positive', 'claude-test')""");

		job = new ContentAnalysisJob(db, ds, fakeSynthesisPort(), fakeVisionPort(), new AnalyticsSettings(db), false);
	}

	@Test
	void 미분석_분류완료_콘텐츠가_분석되어_저장된다() {
		int processed = job.run();

		assertEquals(2, processed); // post_a, post_b (post_c는 미분류라 제외)
		assertEquals(2L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));

		assertEquals("요약: post_a", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'post_a'", String.class));
		assertEquals(9000L, db.queryForObject(
				"SELECT recent_reels_avg_views FROM content_analyses WHERE short_code = 'post_a'", Long.class));
		assertEquals(1, db.queryForObject(
				"SELECT rank_in_recent_reels FROM content_analyses WHERE short_code = 'post_a'", Integer.class));
		assertEquals(0, new java.math.BigDecimal("0.0496").compareTo(db.queryForObject(
				"SELECT recent12_avg_engagement_rate FROM content_analyses WHERE short_code = 'post_a'",
				java.math.BigDecimal.class)));
		assertEquals("high", db.queryForObject(
				"SELECT comment_authenticity_grade FROM content_analyses WHERE short_code = 'post_a'", String.class));

		// 종합 포트에 넘긴 댓글 분포가 comment_classifications 집계와 일치한다
		ContentToAnalyze callForA = synthesisCalls.stream()
				.filter(c -> c.shortCode().equals("post_a")).findFirst().orElseThrow();
		assertEquals(1L, callForA.commentCategoryCounts().get("purchase"));
		assertEquals(1L, callForA.commentCategoryCounts().get("positive"));
	}

	@Test
	void 이미_분석된_콘텐츠는_건너뛴다() {
		job.run();
		synthesisCalls.clear();

		int processed = job.run();

		assertEquals(0, processed);
		assertTrue(synthesisCalls.isEmpty());
		assertEquals(2L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void 댓글_있는데_미분류인_콘텐츠는_대상에서_제외된다() {
		job.run();

		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_c'", Long.class));
		assertFalse(synthesisCalls.stream().anyMatch(c -> c.shortCode().equals("post_c")));
	}

	@Test
	void vlm_off이면_VisionPort를_호출하지_않고_VLM_컬럼은_NULL이다() {
		int processed = job.run(); // 기본 게이트: vlmEnabled=false

		assertEquals(2, processed);
		assertTrue(visionCalls.isEmpty());
		assertNull(db.queryForObject(
				"SELECT detected_brands FROM content_analyses WHERE short_code = 'post_a'", String.class));
		assertNull(db.queryForObject(
				"SELECT sponsored_signal_level FROM content_analyses WHERE short_code = 'post_a'", String.class));
		assertNull(db.queryForObject(
				"SELECT ad_type FROM content_analyses WHERE short_code = 'post_a'", String.class));
	}

	@Test
	void 기준선_없는_콘텐츠는_대상에서_제외되고_배치_슬롯을_잠식하지_않는다() {
		// 윈도우 밖 콘텐츠 재현: contents에는 있지만 기준선 뷰에는 없는 short_code (분류 완료 상태).
		// 정렬상 첫 대상(post_0)이라 — 제외가 안 되면 batch-limit=1 슬롯을 잠식해 아무것도 처리 못 한다.
		db.update("""
				INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, content_type, views, likes, comments)
				VALUES ('post_0', 'acct1', 'https://img/0.jpg', '캡션0', 'reels', 5000, 100, 10)""");
		db.update("""
				INSERT INTO content_comments (id, short_code, author_masked, body, like_count)
				VALUES (10, 'post_0', 'ddd***', '굿', 0)""");
		db.update("""
				INSERT INTO comment_classifications (id, short_code, ai_category, model)
				VALUES (10, 'post_0', 'positive', 'claude-test')""");
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-batch-limit', '1')");

		int processed = job.run();

		assertEquals(1, processed); // 기준선 있는 콘텐츠(post_a)가 슬롯을 차지한다
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_0'", Long.class));
		assertFalse(synthesisCalls.stream().anyMatch(c -> c.shortCode().equals("post_0")));
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
	}

	@Test
	void 빈_종합_텍스트는_저장하지_않고_다른_콘텐츠는_처리된다() {
		// 실전 스모크 재현: LLM이 텍스트 전부 빈 문자열인 Synthesis를 반환.
		// content_analyses는 불변이라 빈 결과가 저장되면 영구 고정 + 재분석 대상에서도 제외된다.
		rewireJob(content -> {
			synthesisCalls.add(content);
			if (content.shortCode().equals("post_a")) {
				return new Synthesis("", "", "", "normal", "");
			}
			return new Synthesis("요약: " + content.shortCode(), "패턴 해석", "댓글 인사이트", "normal", "근거");
		}, false);

		int processed = job.run(); // 빈 결과는 실패 격리 경로로 skip — 예외가 전파되지 않아야 한다

		assertEquals(1, processed); // post_b만 성공
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_b'", Long.class));
	}

	@Test
	void 콘텐츠_하나가_실패해도_나머지는_처리된다() {
		// 포트 대역: post_a만 예외 (모의 LLM 장애)
		rewireJob(content -> {
			if (content.shortCode().equals("post_a")) {
				throw new IllegalStateException("모의 LLM 장애");
			}
			synthesisCalls.add(content);
			return new Synthesis("요약: " + content.shortCode(), "패턴 해석", "댓글 인사이트", "normal", "근거");
		}, false);

		int processed = job.run(); // 예외가 전파되지 않아야 한다

		assertEquals(1, processed); // post_b만 성공
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_b'", Long.class));
	}
}
