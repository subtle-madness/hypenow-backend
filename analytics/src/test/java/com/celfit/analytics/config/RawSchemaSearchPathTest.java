package com.celfit.analytics.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * raw DataSource search_path 회귀 가드 (태스크 K) — 뷰 스키마 선택은 이 한 줄(connection-init-sql)에
 * 걸려 있다. 운영은 기본값 analytics, dev 스테이징은 analytics.raw-schema=analytics_dev 오버라이드.
 * application.yml을 실제 로드(ConfigDataApplicationContextInitializer)해 yml의 init-sql 배선까지 잠근다.
 */
@Testcontainers
class RawSchemaSearchPathTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
				.withInitializer(new ConfigDataApplicationContextInitializer())
				// @ConfigurationProperties 바인딩 후처리기 등록 — 없으면 DataSourceBuilder가 jdbc-url을 못 받는다
				.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
				.withUserConfiguration(DataSourceConfig.class)
				.withPropertyValues(
						"app.datasource.raw.jdbc-url=" + pg.getJdbcUrl(),
						"app.datasource.raw.username=" + pg.getUsername(),
						"app.datasource.raw.password=" + pg.getPassword(),
						"app.datasource.analysis.jdbc-url=" + pg.getJdbcUrl(),
						"app.datasource.analysis.username=" + pg.getUsername(),
						"app.datasource.analysis.password=" + pg.getPassword());
	}

	@Test
	void 기본값은_analytics_스키마를_먼저_본다() {
		runner().run(ctx -> {
			JdbcTemplate raw = ctx.getBean("rawJdbcTemplate", JdbcTemplate.class);
			assertEquals("analytics, public",
					raw.queryForObject("SELECT current_setting('search_path')", String.class));
		});
	}

	@Test
	void raw_schema_프로퍼티가_dev_스키마로_오버라이드한다() {
		runner().withPropertyValues("analytics.raw-schema=analytics_dev").run(ctx -> {
			JdbcTemplate raw = ctx.getBean("rawJdbcTemplate", JdbcTemplate.class);
			assertEquals("analytics_dev, public",
					raw.queryForObject("SELECT current_setting('search_path')", String.class));
		});
	}
}
