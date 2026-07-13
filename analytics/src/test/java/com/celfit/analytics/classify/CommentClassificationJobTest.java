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

	/** 임의 동작 포트로 잡을 다시 배선한다 (LLM 출력 정합·실패 격리 테스트용). */
	void rewireJob(CommentClassificationPort port) {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		job = new CommentClassificationJob(db, ds, port, new AnalyticsSettings(db));
	}

	@BeforeEach
	void setUp() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		portCalls = new ArrayList<>();
		db.update("DROP SCHEMA IF EXISTS analytics CASCADE");
		// 테스트 간 완전 초기화: Flyway 이력과 V1·V2 산출물, raw 대역을 전부 지우고 다시 만든다
		db.update("DROP TABLE IF EXISTS content_analyses, comment_classifications, accounts, contents, content_comments, content_metric_snapshots");
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

	@Test
	void LLM이_id를_누락하거나_없는_id를_반환해도_입력_기준으로_정합된다() {
		// 포트 대역: id 2 결과를 누락하고, 입력에 없는 id 999를 지어낸다 (환각)
		rewireJob(comments -> {
			List<ClassifiedComment> out = new ArrayList<>(comments.stream()
					.filter(c -> c.id() != 2)
					.map(c -> new ClassifiedComment(c.id(), "positive")).toList());
			out.add(new ClassifiedComment(999L, "purchase"));
			return out;
		});

		job.run();

		// 저장 행 수 == 입력 댓글 수 (누락도 유실도 없다)
		assertEquals(3, db.queryForObject("SELECT count(*) FROM comment_classifications", Long.class));
		// 누락된 id는 etc로 합성
		assertEquals("etc", db.queryForObject(
				"SELECT ai_category FROM comment_classifications WHERE id = 2", String.class));
		// 입력에 없는 id는 저장하지 않는다
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM comment_classifications WHERE id = 999", Long.class));
	}

	@Test
	void LLM이_같은_id를_중복_반환해도_첫_결과만_저장된다() {
		// 포트 대역: 모든 결과 뒤에 첫 댓글의 상반된 중복 결과를 덧붙인다
		rewireJob(comments -> {
			List<ClassifiedComment> out = new ArrayList<>(comments.stream()
					.map(c -> new ClassifiedComment(c.id(), "positive")).toList());
			out.add(new ClassifiedComment(comments.get(0).id(), "purchase"));
			return out;
		});

		job.run(); // PK 충돌 없이 완료돼야 한다

		assertEquals(3, db.queryForObject("SELECT count(*) FROM comment_classifications", Long.class));
		assertEquals("positive", db.queryForObject(
				"SELECT ai_category FROM comment_classifications WHERE id = 1", String.class));
	}

	@Test
	void 콘텐츠_하나가_실패해도_나머지는_처리된다() {
		// 포트 대역: post_a의 댓글(id 1 포함)에서만 예외 — 대상 순서상 첫 콘텐츠
		rewireJob(comments -> {
			if (comments.stream().anyMatch(c -> c.id() == 1)) {
				throw new IllegalStateException("모의 LLM 장애");
			}
			return comments.stream().map(c -> new ClassifiedComment(c.id(), "positive")).toList();
		});

		int processed = job.run(); // 예외가 전파되지 않아야 한다

		assertEquals(1, processed); // post_b만 성공
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM comment_classifications WHERE short_code = 'post_a'", Long.class));
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM comment_classifications WHERE short_code = 'post_b'", Long.class));
	}
}
