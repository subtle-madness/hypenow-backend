package com.celfit.monitoring.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 등록 직후 저장·리포스트 백필(08-04) 전용 executor — 등록 동기 응답(was 10초 read timeout 예산)
 * 밖에서 세션 복권 재시도(최대 6회×10s)를 돌린다.
 *
 * <p>단일 스레드인 이유: 등록은 사용자 트리거라 저볼륨이고, 직렬화 자체가 곧 Hiker 응답 캐시
 * 회피 간격이 된다(동시 등록 여러 건이 몰려도 재콜이 자연히 시차를 갖는다). 데몬 스레드라
 * 앱 종료를 막지 않는다 — 종료로 백필이 끊겨도 다음날 새벽 스윕이 백스톱이다.
 *
 * <p>Spring Boot 기본 applicationTaskExecutor와 반드시 이름으로 구분해 주입한다
 * (RegistrationService의 {@code @Qualifier("metricsBackfillExecutor")}) — 타입 주입은 모호성 에러.
 */
@Configuration
public class MetricsBackfillConfig {

	@Bean(name = "metricsBackfillExecutor")
	public Executor metricsBackfillExecutor() {
		return Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "metrics-backfill");
			t.setDaemon(true);
			return t;
		});
	}
}
