package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.monitoring.DigestJob;
import com.celfit.was.monitoring.MonitoringConfig;
import com.celfit.was.v1.monitoring.NoopRegistrationExecutor;
import com.celfit.was.v1.monitoring.RecoverStalePendingScheduler;
import com.celfit.was.v1.monitoring.RegistrationExecutor;
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

	@Test
	void 비활성이면_Noop_실행기만_뜬다() {
		assertThat(context.getBeansOfType(RegistrationExecutor.class)).hasSize(1);
		assertThat(context.getBean(RegistrationExecutor.class)).isInstanceOf(NoopRegistrationExecutor.class);
	}

	@Test
	void 비활성이면_다이제스트_크론과_복구_크론이_없다() {
		// 둘 다 monitoring.enabled 조건부(DigestJob은 monitoring DB 조회, 복구 크론은 실 등록 실행기
		// 필요) — 비활성에서 빈이 남아 있으면 존재하지 않는 monitoring 배선을 참조해 부팅이 깨진다.
		assertThat(context.getBeanNamesForType(DigestJob.class)).isEmpty();
		assertThat(context.getBeanNamesForType(RecoverStalePendingScheduler.class)).isEmpty();
	}
}
