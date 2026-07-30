package com.celfit.was;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// monitoring 모듈과 동일 관용구(MonitoringApplication) — @Scheduled 빈은 각자 monitoring.enabled
// 조건부라 이 애노테이션 자체는 무조건 켜 둬도 무해(빈이 없으면 스케줄러가 아무것도 등록하지 않는다).
@SpringBootApplication
@EnableScheduling
public class WasApplication {
	public static void main(String[] args) {
		SpringApplication.run(WasApplication.class, args);
	}
}
