package com.celfit.analytics.testsupport;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Testcontainers 잡 테스트 공용 DB 초기화.
 * 테이블 목록을 하드코딩해 DROP하는 대신 스키마를 통째로 재생성한다 —
 * 새 미러/분석 테이블이 생겨도 이 헬퍼는 갱신할 필요가 없다.
 */
public final class TestDb {

	private TestDb() {
	}

	/**
	 * public·analytics 스키마를 통째로 비운다 (모든 테이블·뷰·Flyway 이력 포함).
	 * analytics 스키마가 필요한 테스트는 이후 직접 CREATE SCHEMA 한다.
	 */
	public static void reset(JdbcTemplate db) {
		// analytics 뷰가 public 테이블을 참조하므로 analytics부터 지운다
		db.update("DROP SCHEMA IF EXISTS analytics CASCADE");
		db.update("DROP SCHEMA public CASCADE");
		db.update("CREATE SCHEMA public");
	}

	/** 스키마 초기화 후 분석 마이그레이션을 처음부터 다시 적용한다. */
	public static void resetAndMigrate(JdbcTemplate db, DataSource ds) {
		reset(db);
		Flyway.configure().dataSource(ds).locations("classpath:db/migration/analysis")
				.load().migrate();
	}
}
