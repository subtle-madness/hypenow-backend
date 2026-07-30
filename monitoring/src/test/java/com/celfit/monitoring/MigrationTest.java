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
				   ('public','profile_snapshot'), ('public','post_snapshot'),
				   ('public','post_comment'), ('public','profile_meta'))""", Long.class);
		assertThat(tables).isEqualTo(7);
	}

	/** v1.1 P2 표면(V4) — detected_candidate.matched_keywords 컬럼이 실제로 추가됐는지. */
	@Test
	void detected_candidate에_matched_keywords_컬럼이_있다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		Long column = db.queryForObject("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema='public' AND table_name='detected_candidate'
				  AND column_name='matched_keywords'""", Long.class);
		assertThat(column).isEqualTo(1);
	}

	/**
	 * V4 신규 표면도 was_reader가 SELECT할 수 있어야 한다 — V2의 ALTER DEFAULT PRIVILEGES가
	 * V4 이후에 생긴 테이블에도 적용되는지 실제로 확인한다(계약 §6, 별도 GRANT 없음을 전제).
	 */
	@Test
	void was_reader는_P2_표면을_SELECT할_수_있다() {
		var pg = TestDb.container();
		var ds = TestDb.dataSource(pg);
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		var wasReader = new JdbcTemplate(TestDb.wasReaderDataSource(pg));

		assertThat(wasReader.queryForObject("SELECT count(*) FROM post_comment", Long.class)).isZero();
		assertThat(wasReader.queryForObject("SELECT count(*) FROM profile_meta", Long.class)).isZero();
	}
}
