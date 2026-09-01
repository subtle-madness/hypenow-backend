package com.celfit.monitoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MonitoringApplication {
	public static void main(String[] args) {
		// 프록시 HTTPS CONNECT 터널의 Basic 인증 활성화. jdk.internal.net.http.common.Utils가
		// 이 값을 최초 1회만 캐싱하므로 어떤 HttpClient보다 먼저 — SpringApplication.run 전에 —
		// 설정해야 한다(crawler CrawlerApplication과 동일 함정). Spring 배선상 JdkHikerHttp가
		// SelfHttpClient보다 먼저 초기화돼 SelfHttpClient의 static 블록만으로는 이미 캐싱된 뒤라
		// 무효했다(407 전량 실패, 실증됨) — 여기가 정본, SelfHttpClient의 static 블록은 다른
		// 소비자를 위한 최선노력 보험일 뿐이다.
		System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
		SpringApplication.run(MonitoringApplication.class, args);
	}
}
