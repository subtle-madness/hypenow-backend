package com.celfit.was.v1.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * RegistrationExecutor의 임시 구현 — 실행기 태스크(후속)가 대체할 때까지 no-op.
 * pending으로 남은 entries·행은 이 구현이 뜬 동안은 백그라운드 첫 확인 없이 그대로 남는다
 * (계약 위반은 아니다 — 6.27은 접수만 보장하고 첫 확인은 "수 분 내"로 명시).
 */
@Component
public class NoopRegistrationExecutor implements RegistrationExecutor {

	private static final Logger log = LoggerFactory.getLogger(NoopRegistrationExecutor.class);

	@Override
	public void submit(long registrationId) {
		log.info("실행기 미구현: 후속 태스크에서 대체 (registrationId={})", registrationId);
	}
}
