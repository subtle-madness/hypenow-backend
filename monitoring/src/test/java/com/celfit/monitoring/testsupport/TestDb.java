package com.celfit.monitoring.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Testcontainers 공용 초기화 — raw·public 스키마 재생성 + Flyway 재적용. */
public final class TestDb {

	private static PostgreSQLContainer container;

	/** was_reader 롤 생성 DO 블록 — container()·resetAndMigrate() 두 곳이 같은 문장을 쓴다(드리프트 방지). */
	private static final String CREATE_READER_ROLE_SQL = """
			DO $$ BEGIN
			  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'was_reader')
			  THEN CREATE ROLE was_reader LOGIN PASSWORD 'was_reader'; END IF;
			END $$""";

	private TestDb() {
	}

	public static synchronized PostgreSQLContainer container() {
		if (container == null) {
			container = new PostgreSQLContainer("postgres:17-alpine");
			container.start();
			// was_reader 롤은 V2 마이그레이션의 GRANT 대상이라 Flyway가 돌기 전에 반드시 있어야 한다.
			// resetAndMigrate()를 거치는 테스트만 믿으면, 그 헬퍼를 부르지 않고 @DynamicPropertySource로
			// 이 컨테이너를 직접 가리키는 @SpringBootTest(예: RegistrationApiTest)는 같은 JVM에서
			// resetAndMigrate가 먼저 실행됐는지에 따라 결과가 갈린다 — 실행 순서는 머신·파일시스템마다
			// 다를 수 있어 "가끔 되는" 플레이크가 된다. 컨테이너 기동 직후 한 번, 여기서 만들어 두면
			// 어떤 소비자가 먼저 오든 항상 있다.
			createReaderRole(container);
		}
		return container;
	}

	private static void createReaderRole(PostgreSQLContainer pg) {
		try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
				Statement stmt = conn.createStatement()) {
			stmt.execute(CREATE_READER_ROLE_SQL);
		} catch (SQLException e) {
			throw new IllegalStateException("was_reader 롤 생성 실패 — 테스트 컨테이너 초기화 불가", e);
		}
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
		db.update(CREATE_READER_ROLE_SQL);
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
