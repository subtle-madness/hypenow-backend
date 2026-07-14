package com.celfit.was.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * `app` 스키마(서비스 데이터) 전용 Flyway — was가 소유한다 (ARCHITECTURE §3).
 * 같은 analysis DB의 분석 결과 스키마(public)는 analytics 소유라 건드리지 않는다.
 * 공유 dev DB에서 analytics의 flyway_schema_history와 충돌하지 않도록
 * 이력 테이블을 app 스키마 안 별도 이름(flyway_schema_history_app)으로 분리한다.
 * Boot 4는 Flyway 자동설정이 별도 모듈이라 이 수동 빈이 was의 유일한 Flyway.
 */
@Configuration
public class FlywayConfig {

	@Bean(initMethod = "migrate")
	public Flyway appFlyway(DataSource dataSource) {
		return Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration/app")
				.schemas("app")
				.table("flyway_schema_history_app")
				.load();
	}
}
