package com.celfit.was.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * app 스키마 전용 Flyway (서비스 데이터는 was가 소유 — ARCHITECTURE.md §3).
 * was는 DataSource가 analysis DB 하나뿐이라 별도 Qualifier 없이 그대로 사용하고,
 * schemas/createSchemas/defaultSchema로 app 스키마만 격리한다(analytics의 FlywayConfig 패턴).
 * 이력 테이블도 app 스키마에 생겨 analytics 소유 이력(public)과 분리된다.
 */
@Configuration
public class AppFlywayConfig {

	@Bean(initMethod = "migrate")
	public Flyway appFlyway(DataSource dataSource) {
		return Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration/app")
				.schemas("app")
				.createSchemas(true)
				.defaultSchema("app")
				.load();
	}
}
