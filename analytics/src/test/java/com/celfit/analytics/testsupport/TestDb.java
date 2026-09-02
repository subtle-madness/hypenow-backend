package com.celfit.analytics.testsupport;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Testcontainers 잡 테스트 공용 DB 초기화.
 * 테이블 목록을 하드코딩해 DROP하는 대신 스키마를 통째로 재생성한다 —
 * 새 미러/분석 테이블이 생겨도 이 헬퍼는 갱신할 필요가 없다.
 */
public final class TestDb {

	private TestDb() {
	}

	/**
	 * 모듈 전체가 공유하는 Postgres 싱글턴(was의 IntegrationTest와 같은 패턴) — 클래스마다
	 * 컨테이너를 새로 띄우면 기동·초기화가 클래스 수만큼 반복돼 테스트 시간의 절반 안팎을
	 * 차지했다(09-02 실측). 명시적으로 stop하지 않는다 — Testcontainers Ryuk이 JVM 종료 시
	 * 정리한다. 공유 전제: 각 테스트 클래스는 시작 시 {@link #reset}/{@link #resetAndMigrate}
	 * 또는 자체 DROP으로 필요한 상태를 스스로 만든다(이전 클래스의 잔재를 가정하지 않는다).
	 */
	private static final PostgreSQLContainer SHARED = new PostgreSQLContainer("postgres:16-alpine");

	static {
		SHARED.start();
	}

	public static PostgreSQLContainer shared() {
		return SHARED;
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

	/**
	 * raw 쪽 테스트 DataSource — 운영의 connection-init-sql과 같은 효과를 JDBC URL
	 * currentSchema로 낸다(태스크 K). public을 앞에 둬 픽스처 DDL·Flyway는 기존처럼
	 * public에 만들어지고, 무접두어 뷰 조회만 analytics로 폴백된다.
	 */
	public static DriverManagerDataSource rawDataSource(PostgreSQLContainer pg) {
		String sep = pg.getJdbcUrl().contains("?") ? "&" : "?";
		return new DriverManagerDataSource(
				pg.getJdbcUrl() + sep + "currentSchema=public,analytics",
				pg.getUsername(), pg.getPassword());
	}

	/**
	 * 스키마 초기화 후 분석 마이그레이션이 전부 적용된 상태를 만든다.
	 *
	 * <p>매 호출 Flyway 재생(실측 223ms — @BeforeEach 호출자가 많아 런당 수십 초)을 하는 대신,
	 * 첫 호출에 마이그레이션을 템플릿 DB에 한 번만 적용해두고 이후엔 {@code CREATE DATABASE
	 * ... TEMPLATE}로 통째로 복제한다 — 결과 상태(테이블·matview·flyway 이력)는 프레시 재생과
	 * 동일하다. 대상은 공유 컨테이너({@link #shared})의 기본 DB뿐이므로 인자 db/ds는 더 이상
	 * 쓰지 않지만, 호출부 21곳의 시그니처 호환을 위해 유지한다.
	 */
	public static void resetAndMigrate(JdbcTemplate db, DataSource ds) {
		ensureTemplate();
		JdbcTemplate admin = adminDb();
		// WITH (FORCE): 붙어있는 커넥션이 있어도 끊고 지운다(테스트는 DriverManagerDataSource라
		// 상시 커넥션이 없지만, 커넥션 풀을 쓰는 테스트가 생겨도 여기가 막히지 않게)
		admin.update("DROP DATABASE IF EXISTS " + SHARED.getDatabaseName() + " WITH (FORCE)");
		admin.update("CREATE DATABASE " + SHARED.getDatabaseName() + " TEMPLATE " + TEMPLATE_DB);
	}

	private static final String TEMPLATE_DB = "analysis_template";
	private static boolean templateReady;

	private static void ensureTemplate() {
		if (templateReady) {
			return;
		}
		JdbcTemplate admin = adminDb();
		admin.update("DROP DATABASE IF EXISTS " + TEMPLATE_DB + " WITH (FORCE)");
		admin.update("CREATE DATABASE " + TEMPLATE_DB);
		Flyway.configure()
				.dataSource(new DriverManagerDataSource(urlFor(TEMPLATE_DB), SHARED.getUsername(), SHARED.getPassword()))
				.locations("classpath:db/migration/analysis").load().migrate();
		templateReady = true;
	}

	/** 유지관리용 postgres DB 접속 — DROP/CREATE DATABASE는 대상 DB 밖에서만 가능하다. */
	private static JdbcTemplate adminDb() {
		return new JdbcTemplate(new DriverManagerDataSource(urlFor("postgres"), SHARED.getUsername(), SHARED.getPassword()));
	}

	private static String urlFor(String dbName) {
		return "jdbc:postgresql://" + SHARED.getHost() + ":" + SHARED.getMappedPort(5432) + "/" + dbName;
	}
}
