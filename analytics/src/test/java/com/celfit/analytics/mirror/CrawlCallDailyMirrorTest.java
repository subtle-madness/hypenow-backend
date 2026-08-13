package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.celfit.analytics.testsupport.TestDb;
import com.celfit.contract.analysis.CrawlCallDaily;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * crawl_call_daily 미러의 뷰→테이블 왕복 검증. 이 미러는 기존 미러 record 중 유일하게
 * {@code LocalDate} 컴포넌트를 갖는다 — MirrorJob이 date 컬럼을 rs.getObject(n, LocalDate.class)로
 * 읽고 다시 쓰는 경로가 실제로 도는지 여기서 실증한다(안 하면 운영 미러 첫 실행에서 터진다).
 * 뷰 정의 자체의 집계 규칙은 SQL 하니스(analytics/test/30_crawl_cost.test.sql)가 검증한다.
 */
@Testcontainers
class CrawlCallDailyMirrorTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	JdbcTemplate db;
	MirrorJob job;

	@BeforeEach
	void setUp() {
		// 운영과 같은 무접두어 뷰 조회 경로를 쓰려면 currentSchema에 analytics가 있어야 한다
		// (MirrorJob은 "SELECT * FROM v_crawl_call_daily"로 읽는다) — TestDb가 그 URL을 만든다.
		DataSource ds = TestDb.rawDataSource(pg);
		db = new JdbcTemplate(ds);
		job = new MirrorJob(db, ds);
		db.update("DROP SCHEMA IF EXISTS analytics CASCADE");
		db.update("DROP TABLE IF EXISTS crawl_call_daily");
		db.update("DROP TABLE IF EXISTS crawl_call_src");
		db.update("CREATE SCHEMA analytics");
		// 소스는 최소 픽스처다 — 실제 30_crawl_cost.sql을 여기 복사하지 않는다. 그 집계 규칙은
		// SQL 하니스가 실 스키마로 검증하고, 여기서 볼 것은 date 컬럼의 JDBC 왕복뿐이다.
		// 뷰 정의를 복제하면 원본이 바뀌어도 이 테스트는 스테일한 사본으로 계속 통과한다.
		db.update("CREATE TABLE crawl_call_src (job text, called_on date, calls bigint)");
		db.update("""
				CREATE VIEW analytics.v_crawl_call_daily AS
				SELECT job, called_on, calls FROM crawl_call_src
				""");
		db.update("""
				CREATE TABLE crawl_call_daily (job text NOT NULL, called_on date NOT NULL,
				    calls bigint NOT NULL, PRIMARY KEY (job, called_on))
				""");
	}

	@Test
	void date_컬럼이_LocalDate로_왕복한다() {
		db.update("""
				INSERT INTO crawl_call_src VALUES
				 ('COLLECT', date '2026-06-05', 3), ('COLLECT', date '2026-06-06', 7)
				""");

		int moved = job.mirror(new MirrorSpec<>("v_crawl_call_daily", "crawl_call_daily", CrawlCallDaily.class));

		assertEquals(2, moved);
		// 타입을 명시해 읽는다 — queryForList(sql)는 date를 java.sql.Date로 돌려주므로
		// LocalDate와 직접 비교하면 왕복이 정상이어도 실패한다.
		assertEquals(List.of(LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 6)),
				db.queryForList("SELECT called_on FROM crawl_call_daily ORDER BY called_on", LocalDate.class));
		assertEquals(List.of(3L, 7L),
				db.queryForList("SELECT calls FROM crawl_call_daily ORDER BY called_on", Long.class));
	}
}
