package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.monitoring.DigestJob;
import com.celfit.was.monitoring.MonitoringConfig;
import com.celfit.was.v1.account.AccountDeletionService;
import com.celfit.was.v1.brandmonitoring.V1BrandAccountService;
import com.celfit.was.v1.brandmonitoring.V1BrandAccountsController;
import com.celfit.was.v1.monitoring.NoopRegistrationExecutor;
import com.celfit.was.v1.monitoring.RecoverStalePendingScheduler;
import com.celfit.was.v1.monitoring.RegistrationExecutor;
import com.celfit.was.v1.perfdashboard.V1PerformanceDashboardController;
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
	void 비활성이면_브랜드_계정_표면이_없고_탈퇴_배선은_멀쩡하다() {
		// 브랜드 서비스는 monitoring 전용 빈(명령 클라이언트·브랜드 조회)에 전면 의존한다 —
		// 조건부가 아니면 여기서 부팅이 깨진다. AccountDeletionService는 Optional 주입이라 무손상.
		assertThat(context.getBeanNamesForType(V1BrandAccountService.class)).isEmpty();
		assertThat(context.getBeanNamesForType(V1BrandAccountsController.class)).isEmpty();
		assertThat(context.getBeanNamesForType(AccountDeletionService.class)).hasSize(1);
	}

	@Test
	void 비활성이어도_성과_대시보드_표면은_살아_있다() {
		// 대시보드는 브랜드 연동이 없는 유저(레거시 개인 추적만)도 쓰는 화면이라 브랜드 표면과 달리
		// 조건부가 아니다 — 어셈블러가 브랜드 의존을 Optional로 받아 비활성이면 레거시만 조립한다.
		// 실수로 @ConditionalOnProperty가 붙으면 표면이 조용히 사라지므로 여기서 못박는다.
		assertThat(context.getBeanNamesForType(V1PerformanceDashboardController.class)).hasSize(1);
	}

	@Test
	void 비활성이면_다이제스트_크론과_복구_크론이_없다() {
		// 둘 다 monitoring.enabled 조건부(DigestJob은 monitoring DB 조회, 복구 크론은 실 등록 실행기
		// 필요) — 비활성에서 빈이 남아 있으면 존재하지 않는 monitoring 배선을 참조해 부팅이 깨진다.
		assertThat(context.getBeanNamesForType(DigestJob.class)).isEmpty();
		assertThat(context.getBeanNamesForType(RecoverStalePendingScheduler.class)).isEmpty();
	}
}
