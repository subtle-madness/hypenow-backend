package com.celfit.was.monitoring;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/**
 * 모니터링 통신 계층 조립 — monitoring.enabled=true일 때만 뜬다(기본 비활성: 컨테이너 미배포
 * 환경에서 was 부팅 무영향). 스펙 §3: monitoring용 HikariDataSource·JdbcClient는 빈으로
 * 노출하지 않는다 — DataSource·JdbcClient 자동구성이 모두 @ConditionalOnMissingBean이라
 * 빈으로 두면 기존 analysis DB 배선(세션 JDBC·app Flyway·전 리포지토리 주입)이 깨진다.
 */
@Configuration
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class MonitoringConfig {

	private static final Logger log = LoggerFactory.getLogger(MonitoringConfig.class);

	private final HikariDataSource monitoringDataSource;
	private final JdbcClient monitoringJdbc;
	private final RestClient monitoringRestClient;

	public MonitoringConfig(
			@Value("${monitoring.api.base-url:http://monitoring:8083}") String baseUrl,
			@Value("${monitoring.datasource.url}") String dbUrl,
			@Value("${monitoring.datasource.username}") String dbUsername,
			@Value("${monitoring.datasource.password}") String dbPassword) {
		// PATCH(기간 연장) 때문에 JDK HttpClient 팩토리 — 타임아웃은 계약 §1 권고 최대치로 단일화(스펙 §4)
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
		requestFactory.setReadTimeout(Duration.ofSeconds(10));
		this.monitoringRestClient = RestClient.builder()
				.requestFactory(requestFactory)
				.baseUrl(baseUrl)
				.build();

		// 커넥션 풀 획득은 마지막 — 이후 라인이 예외를 던지면 @PreDestroy 미등록 상태로 풀이 새기 때문
		HikariConfig hikari = new HikariConfig();
		hikari.setJdbcUrl(dbUrl);
		hikari.setUsername(dbUsername);
		hikari.setPassword(dbPassword);
		hikari.setMaximumPoolSize(3);          // 조회 전용·저트래픽 (스펙 §3)
		hikari.setPoolName("monitoring-ro");
		this.monitoringDataSource = new HikariDataSource(hikari);
		this.monitoringJdbc = JdbcClient.create(monitoringDataSource);

		log.info("모니터링 통신 계층 활성 base-url={} (조회 풀 monitoring-ro, max 3)", baseUrl);
	}

	/** 내부 접근자 — 빈이 아니다. 도메인 빈 조립과 테스트에서만 쓴다. */
	public JdbcClient monitoringJdbc() {
		return monitoringJdbc;
	}

	public RestClient monitoringRestClient() {
		return monitoringRestClient;
	}

	@Bean
	MonitoringCommandClient monitoringCommandClient() {
		return new MonitoringCommandClient(monitoringRestClient);
	}

	@Bean
	MonitoringReadRepository monitoringReadRepository() {
		return new MonitoringReadRepository(monitoringJdbc);
	}

	@PreDestroy
	void close() {
		monitoringDataSource.close();
	}
}
