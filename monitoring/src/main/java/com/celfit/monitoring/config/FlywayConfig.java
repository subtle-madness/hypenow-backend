package com.celfit.monitoring.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** monitoring DB 전용 Flyway. crawler·analysis DB에는 절대 걸지 않는다. */
@Configuration
public class FlywayConfig {

	@Bean(initMethod = "migrate")
	public Flyway monitoringFlyway(DataSource dataSource) {
		return Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.load();
	}
}
