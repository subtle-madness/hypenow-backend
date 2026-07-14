package com.celfit.was.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 경과일 계산용 Clock — 테스트에서 고정 주입할 수 있게 빈으로 분리. */
@Configuration
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
