package com.celfit.analytics.classify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ClassifiedComment;
import com.celfit.analytics.llm.CommentClassificationPort;
import com.celfit.analytics.llm.CommentToClassify;
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

@Testcontainers
class CommentClassificationJobTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	JdbcTemplate db;
	CommentClassificationJob job;
	List<List<CommentToClassify>> portCalls;

	/** fake 포트: 전부 positive로 분류, 호출 내역 기록. */
	CommentClassificationPort fakePort() {
		return comments -> {
			portCalls.add(comments);
			return comments.stream().map(c -> new ClassifiedComment(c.id(), "positive")).toList();
		};
	}

	@BeforeEach
	void setUp() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		portCalls = new ArrayList<>();
		db.update("DROP SCHEMA IF EXISTS analytics CASCADE");
		// 테스트 간 완전 초기화: Flyway 이력과 V1·V2 산출물, raw 대역을 전부 지우고 다시 만든다
		db.update("DROP TABLE IF EXISTS comment_classifications, accounts, contents, content_comments");
		db.update("DROP TABLE IF EXISTS app_setting, src_comments, flyway_schema_history");
		Flyway.configure().dataSource(ds).locations("classpath:db/migration/analysis")
				.baselineOnMigrate(true).baselineVersion("0").load().migrate();
		// 테스트용 raw 대역: 서빙 뷰와 같은 모양의 뷰 + app_setting
		db.update("CREATE SCHEMA analytics");
		db.update("CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL)");
		db.update("""
				CREATE TABLE src_comments (id bigint, short_code text, author_masked text, body text, like_count bigint)""");
		db.update("""
				CREATE VIEW analytics.v_content_comments AS
				SELECT id, short_code, author_masked, body, like_count FROM src_comments""");
		db.update("INSERT INTO src_comments VALUES (1,'post_a','aaa***','어디서 사요?',3),(2,'post_a','bbb***','예뻐요',1),(3,'post_b','ccc***','좋아요',0)");
		JdbcTemplate raw = db;
		job = new CommentClassificationJob(raw, ds, fakePort(), new AnalyticsSettings(raw));
	}

	@Test
	void 미분류_콘텐츠의_댓글을_분류해_저장한다() {
		int processed = job.run();

		assertEquals(2, processed); // post_a, post_b
		assertEquals(3, db.queryForObject("SELECT count(*) FROM comment_classifications", Long.class));
		assertEquals("positive", db.queryForObject(
				"SELECT ai_category FROM comment_classifications WHERE id = 1", String.class));
	}

	@Test
	void 이미_분류된_콘텐츠는_건너뛴다() {
		job.run();
		portCalls.clear();

		int processed = job.run();

		assertEquals(0, processed);
		assertTrue(portCalls.isEmpty());
	}

	@Test
	void 배치_상한이_대상_수를_제한한다() {
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-batch-limit', '1')");

		int processed = job.run();

		assertEquals(1, processed);
		assertEquals(1L, db.queryForObject(
				"SELECT count(DISTINCT short_code) FROM comment_classifications", Long.class));
	}

	@Test
	void 재분류_시_콘텐츠_단위로_교체된다_중복_없음() {
		job.run();
		db.update("DELETE FROM comment_classifications WHERE short_code = 'post_b'"); // post_b만 재대상화

		job.run();

		assertEquals(3, db.queryForObject("SELECT count(*) FROM comment_classifications", Long.class));
	}
}
