package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.monitoring.MonitoringConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * monitoring.enabled=true — 모니터링 구성이 뜨되, 기본 DataSource·JdbcClient 자동구성이
 * back-off 하지 않는다(인프라 빈 비노출 설계 검증 — 스펙 §3).
 */
@TestPropertySource(properties = "monitoring.enabled=true")
class MonitoringEnabledConfigTest extends IntegrationTest {

	@DynamicPropertySource
	static void monitoringDatasource(DynamicPropertyRegistry registry) {
		registry.add("monitoring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("monitoring.datasource.username", POSTGRES::getUsername);
		registry.add("monitoring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired
	ApplicationContext context;

	@Test
	void 활성이면_모니터링_구성이_뜬다() {
		assertThat(context.getBeanNamesForType(MonitoringConfig.class)).hasSize(1);
	}

	@Test
	void 기본_JdbcClient는_여전히_하나뿐이다() {
		// monitoring 내부 JdbcClient가 빈으로 새어 나오면 여기가 2가 되며 기존 리포지토리 주입이 전부 깨진다
		assertThat(context.getBeansOfType(JdbcClient.class)).hasSize(1);
	}

	@Test
	void 기본_JdbcClient는_모니터링_내부_것과_다른_인스턴스다() {
		// 사고 시나리오(모니터링 JdbcClient가 빈으로 새어 자동구성 back-off)에서는 컨텍스트의
		// 유일한 JdbcClient가 모니터링 것이 된다 — 개수 단언으로는 구분 불가, identity로 잡는다
		MonitoringConfig config = context.getBean(MonitoringConfig.class);
		assertThat(context.getBean(JdbcClient.class)).isNotSameAs(config.monitoringJdbc());
	}

	@Test
	void 모니터링_DB_조회가_동작한다() {
		MonitoringConfig config = context.getBean(MonitoringConfig.class);
		Integer one = config.monitoringJdbc().sql("SELECT 1").query(Integer.class).single();
		assertThat(one).isEqualTo(1);
	}
}
