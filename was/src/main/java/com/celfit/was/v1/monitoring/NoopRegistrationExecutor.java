package com.celfit.was.v1.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RegistrationExecutor의 폴백 구현 — monitoring 서브시스템이 비활성(monitoring.enabled=false,
 * 컨테이너 미배포 환경 포함)일 때만 뜬다. {@link MonitoringRegistrationExecutor}와 정확히 반대
 * 조건(같은 프로퍼티의 역)이라 두 빈이 동시에 뜨는 경우가 없다 — 컴포넌트 스캔 순서에 기대는
 * {@code @ConditionalOnMissingBean}보다 결정적이라 이 조건식을 택했다.
 * pending으로 남은 entries·행은 이 구현이 뜬 동안은 백그라운드 첫 확인 없이 그대로 남는다
 * (계약 위반은 아니다 — 6.27은 접수만 보장하고 첫 확인은 "수 분 내"로 명시).
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "false", matchIfMissing = true)
public class NoopRegistrationExecutor implements RegistrationExecutor {

	private static final Logger log = LoggerFactory.getLogger(NoopRegistrationExecutor.class);

	@Override
	public void submit(long registrationId) {
		log.info("실행기 미구현: 후속 태스크에서 대체 (registrationId={})", registrationId);
	}
}
