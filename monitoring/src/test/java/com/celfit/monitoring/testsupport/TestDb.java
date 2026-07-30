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
		}
		return container;
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

	/**
	 * 알람 발송기가 읽는 analysis DB app 스키마 흉내 — 계약 v2 §6이 정의한 **두 객체만** 만든다.
	 * was Flyway를 여기서 돌리지 않는 이유: monitoring이 was 마이그레이션에 빌드 의존을 갖게 되고,
	 * 실제로 읽는 컬럼(email·event_type)보다 훨씬 넓은 표면을 테스트가 보증하게 된다.
	 * resetAndMigrate가 public·raw만 지우므로 app은 여기서 따로 초기화한다.
	 */
	public static void resetAppFixture(JdbcTemplate db) {
		db.update("DROP SCHEMA IF EXISTS app CASCADE");
		db.update("CREATE SCHEMA app");
		db.update("""
				CREATE TABLE app.users (
				    id    bigserial PRIMARY KEY,
				    email text
				)""");
		db.update("""
				CREATE TABLE app.monitoring_email_opt_outs (
				    user_id    bigint      NOT NULL,
				    event_type text        NOT NULL,
				    created_at timestamptz NOT NULL DEFAULT now(),
				    PRIMARY KEY (user_id, event_type)
				)""");
	}
}
