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
				   ('public','profile_snapshot'), ('public','post_snapshot'))""", Long.class);
		assertThat(tables).isEqualTo(5);
	}
}
