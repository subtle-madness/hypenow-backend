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

	/**
	 * 스키마 초기화 후 monitoring 마이그레이션이 전부 적용된 상태를 만든다.
	 *
	 * <p>매 호출 Flyway 재생 대신, 첫 호출에 마이그레이션을 템플릿 DB에 한 번만 적용해두고
	 * 이후엔 {@code CREATE DATABASE ... TEMPLATE}로 통째로 복제한다(analytics TestDb와 동일
	 * 기법 — 결과 상태 동등성은 TemplateEquivalenceCheck가 고정). @BeforeEach 호출자가 21개
	 * 클래스라 재생 반복이 모듈 테스트 시간의 큰 몫이었다. 대상은 공유 컨테이너의 기본 DB뿐
	 * 이므로 인자 db/ds는 더 이상 쓰지 않지만 호출부 시그니처 호환을 위해 유지한다.
	 *
	 * <p>구 구현(스키마 단위 DROP)과 달리 DB를 통째로 바꾸므로 app 등 다른 스키마도 함께
	 * 사라진다 — app 픽스처가 필요한 테스트는 이 호출 뒤에 {@link #resetAppFixture}를 부를 것
	 * (현재 그 순서를 어기는 호출자는 없다). was_reader 롤은 클러스터 수준이라 살아남고,
	 * V2 GRANT가 만든 DB 내 권한은 템플릿에 담겨 복제된다.
	 */
	public static void resetAndMigrate(JdbcTemplate db, DataSource ds) {
		ensureTemplate();
		JdbcTemplate admin = adminDb();
		// WITH (FORCE): @SpringBootTest 컨텍스트 캐시가 물고 있는 유휴 풀 커넥션이 있어도 끊는다
		// (Hikari는 다음 대여 때 isValid로 죽은 커넥션을 걸러 재접속하므로 무해)
		admin.update("DROP DATABASE IF EXISTS " + container().getDatabaseName() + " WITH (FORCE)");
		admin.update("CREATE DATABASE " + container().getDatabaseName() + " TEMPLATE " + TEMPLATE_DB);
	}

	private static final String TEMPLATE_DB = "monitoring_template";
	private static boolean templateReady;

	private static synchronized void ensureTemplate() {
		if (templateReady) {
			return;
		}
		JdbcTemplate admin = adminDb();
		admin.update("DROP DATABASE IF EXISTS " + TEMPLATE_DB + " WITH (FORCE)");
		admin.update("CREATE DATABASE " + TEMPLATE_DB);
		// V2 GRANT 대상 롤은 container()가 이미 만들었지만, 방어적으로 한 번 더(멱등 DO 블록)
		admin.update(CREATE_READER_ROLE_SQL);
		Flyway.configure()
				.dataSource(new DriverManagerDataSource(urlFor(TEMPLATE_DB), container().getUsername(), container().getPassword()))
				.locations("classpath:db/migration").load().migrate();
		templateReady = true;
	}

	/** 유지관리용 postgres DB 접속 — DROP/CREATE DATABASE는 대상 DB 밖에서만 가능하다. */
	private static JdbcTemplate adminDb() {
		return new JdbcTemplate(new DriverManagerDataSource(urlFor("postgres"), container().getUsername(), container().getPassword()));
	}

	private static String urlFor(String dbName) {
		return "jdbc:postgresql://" + container().getHost() + ":" + container().getMappedPort(5432) + "/" + dbName;
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
