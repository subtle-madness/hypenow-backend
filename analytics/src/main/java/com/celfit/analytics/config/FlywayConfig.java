package com.celfit.analytics.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * analysis DB 전용 Flyway (분석 결과 스키마는 analytics가 소유 — ARCHITECTURE.md §3).
 * raw DB에는 절대 걸지 않는다 (crawler 소유). Boot 4는 Flyway 자동설정이 별도 모듈(spring-boot-flyway)이라
 * 이 프로젝트엔 없음 — 이 수동 빈이 유일한 Flyway.
 * baseline: analysis DB에는 과거 산출물 테이블이 남아 있을 수 있어 baselineOnMigrate,
 * 단 baselineVersion=0으로 두어 V1부터 전부 적용되게 한다.
 */
@Configuration
public class FlywayConfig {

	@Bean(initMethod = "migrate")
	public Flyway analysisFlyway(@Qualifier("analysisDataSource") DataSource analysisDataSource) {
		return Flyway.configure()
				.dataSource(analysisDataSource)
				.locations("classpath:db/migration/analysis")
				.baselineOnMigrate(true)
				.baselineVersion("0")
				.load();
	}
}
