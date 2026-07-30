package com.celfit.monitoring.alarm;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 알람 모듈 조립. Clock을 빈으로 두는 이유는 발송 잡의 디바운스·due 판정을 테스트가 고정하기 위해서다. */
@Configuration
public class AlarmConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
