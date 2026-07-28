package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.celfit.analytics.testsupport.TestDb;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class MirrorJobTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	JdbcTemplate db;
	MirrorJob job;

	record FixtureRow(Long id, String name, Long score) {}

	record MismatchRow(Long id, String label) {}

	static final MirrorSpec<FixtureRow> SPEC =
			new MirrorSpec<>("v_fixture", "fixture_row", FixtureRow.class);

	@BeforeEach
	void setUp() {
		DataSource ds = TestDb.rawDataSource(pg);
		db = new JdbcTemplate(ds);
		job = new MirrorJob(db, ds);
		// 테스트 간 완전 초기화: 스키마 통째 재생성 (이 테스트는 Flyway 미사용)
		TestDb.reset(db);
		db.update("CREATE SCHEMA analytics");
		db.update("CREATE TABLE fixture_src (id bigint, name text, score bigint)");
		db.update("CREATE VIEW analytics.v_fixture AS SELECT id, name, score FROM fixture_src");
		db.update("CREATE TABLE fixture_row (id bigint, name text, score bigint)");
		db.update("INSERT INTO fixture_src VALUES (1,'a',10),(2,'b',20)");
	}

	@Test
	void 뷰_결과를_record로_매핑해_테이블에_붓는다() {
		int copied = job.mirror(SPEC);

		assertEquals(2, copied);
		List<Map<String, Object>> rows = db.queryForList("SELECT * FROM fixture_row ORDER BY id");
		assertEquals(2, rows.size());
		assertEquals(1L, rows.get(0).get("id"));
		assertEquals("a", rows.get(0).get("name"));
		assertEquals(10L, rows.get(0).get("score"));
	}

	@Test
	void 재실행은_전체_교체다_잔재가_남지_않는다() {
		job.mirror(SPEC);
		db.update("DELETE FROM fixture_src WHERE id = 1");
		db.update("INSERT INTO fixture_src VALUES (3,'c',30)");

		job.mirror(SPEC);

		List<Long> ids = db.queryForList("SELECT id FROM fixture_row ORDER BY id", Long.class);
		assertEquals(List.of(2L, 3L), ids);
	}

	@Test
	void 뷰_컬럼과_record_필드가_다르면_즉시_실패하고_테이블은_건드리지_않는다() {
		db.update("INSERT INTO fixture_row VALUES (99,'keep',0)");
		MirrorSpec<MismatchRow> bad =
				new MirrorSpec<>("v_fixture", "fixture_row", MismatchRow.class);

		assertThrows(IllegalStateException.class, () -> job.mirror(bad));

		assertEquals(1, db.queryForObject("SELECT count(*) FROM fixture_row", Long.class));
	}
}
