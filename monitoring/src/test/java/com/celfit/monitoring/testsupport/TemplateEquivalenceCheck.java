package com.celfit.monitoring.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * resetAndMigrate의 템플릿 DB 복제 경로가 프레시 경로(스키마 DROP + Flyway 직접 재생)와
 * 동일한 DB 상태를 만든다는 불변식의 회귀 가드(analytics의 동명 가드와 같은 방법) —
 * 스키마·제약·인덱스·뷰·함수·트리거·시퀀스·Flyway 이력·롤 권한·전 테이블 데이터 해시를
 * 지문으로 떠서 대조한다. 이 불변식이 깨지면 resetAndMigrate를 쓰는 모든 테스트의 전제가
 * 조용히 무너진다.
 */
class TemplateEquivalenceCheck {

	@Test
	void 템플릿_복제와_프레시_재생은_같은_DB_상태를_만든다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);

		// 신 경로 먼저: 템플릿 복제 (프레시 경로를 먼저 돌리면 그 잔재 DB 위 상태가 기준이 된다)
		TestDb.resetAndMigrate(db, ds);
		String template = fingerprint(db);

		// 구 경로: 스키마 단위 DROP + Flyway 직접 재생 (구 resetAndMigrate 구현 그대로)
		db.update("DROP SCHEMA IF EXISTS raw CASCADE");
		db.update("DROP SCHEMA public CASCADE");
		db.update("CREATE SCHEMA public");
		Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
		String fresh = fingerprint(db);

		assertEquals(fresh, template);
	}

	private String fingerprint(JdbcTemplate db) {
		StringBuilder sb = new StringBuilder();
		dump(sb, db, "columns", """
				SELECT table_schema, table_name, column_name, data_type, is_nullable, column_default
				FROM information_schema.columns
				WHERE table_schema NOT IN ('pg_catalog','information_schema')
				ORDER BY 1,2,3""");
		dump(sb, db, "constraints", """
				SELECT n.nspname, c.conrelid::regclass::text, c.conname, pg_get_constraintdef(c.oid)
				FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
				WHERE n.nspname NOT IN ('pg_catalog','information_schema')
				ORDER BY 1,2,3""");
		dump(sb, db, "indexes", """
				SELECT schemaname, tablename, indexname, indexdef FROM pg_indexes
				WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY 1,2,3""");
		dump(sb, db, "views", """
				SELECT schemaname, viewname, definition FROM pg_views
				WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY 1,2""");
		dump(sb, db, "functions", """
				SELECT n.nspname, p.proname, pg_get_functiondef(p.oid)
				FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
				WHERE n.nspname NOT IN ('pg_catalog','information_schema') ORDER BY 1,2""");
		dump(sb, db, "triggers", """
				SELECT event_object_schema, event_object_table, trigger_name, action_statement
				FROM information_schema.triggers ORDER BY 1,2,3""");
		dump(sb, db, "sequences", """
				SELECT schemaname, sequencename, last_value FROM pg_sequences ORDER BY 1,2""");
		dump(sb, db, "flyway", """
				SELECT installed_rank, version, description, checksum, success
				FROM flyway_schema_history ORDER BY installed_rank""");
		// V2 GRANT가 만든 was_reader의 DB 내 권한도 복제돼야 한다
		dump(sb, db, "grants", """
				SELECT table_schema, table_name, privilege_type
				FROM information_schema.table_privileges WHERE grantee = 'was_reader'
				ORDER BY 1,2,3""");
		for (String t : db.queryForList("""
				SELECT table_schema || '.' || table_name FROM information_schema.tables
				WHERE table_schema NOT IN ('pg_catalog','information_schema')
				  AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
				ORDER BY 1""", String.class)) {
			dump(sb, db, "data:" + t,
					"SELECT count(*), coalesce(sum(hashtext(x::text)), 0) FROM " + t + " x");
		}
		return sb.toString();
	}

	private void dump(StringBuilder sb, JdbcTemplate db, String label, String sql) {
		sb.append("## ").append(label).append('\n');
		for (var row : db.queryForList(sql)) {
			sb.append(row).append('\n');
		}
	}
}
