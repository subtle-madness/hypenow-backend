package com.celfit.monitoring.testsupport;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Testcontainers 공용 초기화 — raw·public 스키마 재생성 + Flyway 재적용. */
public final class TestDb {

	private static PostgreSQLContainer container;

	private TestDb() {
	}

	public static synchronized PostgreSQLContainer container() {
		if (container == null) {
			container = new PostgreSQLContainer("postgres:17-alpine");
			container.start();
			// was_reader 역할은 여기서 한 번만 만든다. 예전에는 resetAndMigrate()(JdbcTemplate 기반 테스트만
			// 호출)가 만들었는데, @SpringBootTest 클래스는 FlywayConfig가 컨텍스트 기동 시 곧장 V2를 적용해서
			// 역할이 먼저 안 만들어져 있으면 GRANT ... TO was_reader가 "role does not exist"로 죽는다.
			// 실행 순서가 우연히 맞아떨어질 때만 통과하던 문제라 컨테이너 시작 시점에 못박는다.
			ensureWasReaderRole();
		}
		return container;
	}

	private static void ensureWasReaderRole() {
		var ds = dataSource(container);
		new JdbcTemplate(ds).update("""
				DO $$ BEGIN
				  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'was_reader')
				  THEN CREATE ROLE was_reader LOGIN PASSWORD 'was_reader'; END IF;
				END $$""");
	}

	public static DriverManagerDataSource dataSource(PostgreSQLContainer pg) {
		return new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
	}

	/** was 읽기 전용 계정 시점의 DataSource — 권한 검증 테스트용. */
	public static DriverManagerDataSource wasReaderDataSource(PostgreSQLContainer pg) {
		return new DriverManagerDataSource(pg.getJdbcUrl(), "was_reader", "was_reader");
	}

	public static void resetAndMigrate(JdbcTemplate db, DataSource ds) {
		db.update("DROP SCHEMA IF EXISTS raw CASCADE");
		db.update("DROP SCHEMA public CASCADE");
		db.update("CREATE SCHEMA public");
		db.update("""
				DO $$ BEGIN
				  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'was_reader')
				  THEN CREATE ROLE was_reader LOGIN PASSWORD 'was_reader'; END IF;
				END $$""");
		Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
	}
}
