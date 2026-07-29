package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.monitoring.MonitoringConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/** monitoring.enabled 미설정(기본 false) — 모니터링 빈이 아예 안 뜨고 기존 배선 무손상. */
class MonitoringDisabledTest extends IntegrationTest {

	@Autowired
	ApplicationContext context;

	@Test
	void 비활성_기본값이면_모니터링_구성이_없다() {
		assertThat(context.getBeanNamesForType(MonitoringConfig.class)).isEmpty();
	}

	@Test
	void 기본_JdbcClient는_하나뿐이다() {
		assertThat(context.getBeansOfType(JdbcClient.class)).hasSize(1);
	}
}
