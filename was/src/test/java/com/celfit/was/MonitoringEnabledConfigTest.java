package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.monitoring.DigestJob;
import com.celfit.was.monitoring.MonitoringConfig;
import com.celfit.was.v1.monitoring.MonitoringRegistrationExecutor;
import com.celfit.was.v1.monitoring.NoopRegistrationExecutor;
import com.celfit.was.v1.monitoring.RecoverStalePendingScheduler;
import com.celfit.was.v1.monitoring.RegistrationExecutor;
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
 *
 * <p>digest·recover 크론은 "-"로 봉인한다(#183 관례, monitoring.alarm.dispatch-cron과 동일 —
 * Spring @Scheduled cron="-"는 비활성). 봉인하지 않으면 이 테스트 클래스가 살아있는 동안 실제
 * cron이 등록돼(기본값 09:00 KST·10분 간격) 다른 테스트와 타이밍이 우연히 겹칠 여지가 생긴다 —
 * 이 클래스는 빈 배선만 검증하고, run() 자체는 DigestJobTest가 직접 호출해 결정론적으로 검증한다.
 */
@TestPropertySource(properties = { "monitoring.enabled=true", "monitoring.digest.cron=-",
		"monitoring.digest.catchup-cron=-", "monitoring.recover.cron=-" })
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

	@Test
	void 활성이면_실_실행기가_뜨고_Noop은_안_뜬다() {
		assertThat(context.getBeansOfType(RegistrationExecutor.class)).hasSize(1);
		assertThat(context.getBean(RegistrationExecutor.class)).isInstanceOf(MonitoringRegistrationExecutor.class);
		assertThat(context.getBeanNamesForType(NoopRegistrationExecutor.class)).isEmpty();
	}

	@Test
	void 활성이면_다이제스트_크론과_복구_크론이_뜬다() {
		assertThat(context.getBeanNamesForType(DigestJob.class)).hasSize(1);
		assertThat(context.getBeanNamesForType(RecoverStalePendingScheduler.class)).hasSize(1);
	}
}
