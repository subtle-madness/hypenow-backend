package com.celfit.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MigrationTest {

	@Test
	void 마이그레이션이_핵심_테이블을_만든다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		Long tables = db.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				WHERE (table_schema, table_name) IN
				  (('raw','fetch_payload'), ('public','target'), ('public','detected_candidate'),
				   ('public','profile_snapshot'), ('public','post_snapshot'), ('public','alarm_event'))""",
				Long.class);
		assertThat(tables).isEqualTo(6);
	}

	/** user_id는 expand 단계라 nullable이어야 한다 — NOT NULL이면 기존 운영 행 때문에 마이그레이션이 실패한다. */
	@Test
	void target_user_id는_nullable로_추가된다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		assertThat(db.queryForObject("""
				SELECT is_nullable FROM information_schema.columns
				WHERE table_name='target' AND column_name='user_id'""", String.class))
				.isEqualTo("YES");
	}
}
