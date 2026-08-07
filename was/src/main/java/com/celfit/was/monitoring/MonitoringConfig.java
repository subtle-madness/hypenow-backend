package com.celfit.was.monitoring;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
	private final ThreadPoolTaskExecutor registrationTaskExecutor;

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
		hikari.setInitializationFailTimeout(-1);   // 지연 초기화 — monitoring DB 장애가 was 부팅을 막지 않게(부가 서브시스템). 오설정은 첫 조회 시점 예외로 드러난다
		hikari.setPoolName("monitoring-ro");
		this.monitoringDataSource = new HikariDataSource(hikari);
		this.monitoringJdbc = JdbcClient.create(monitoringDataSource);

		// 등록 백그라운드 실행기 전용 풀 — 등록 접수(6.27)는 동기로 끝나고, 첫 확인(monitoring 호출)만
		// 여기서 돈다. 코어=최대=2(등록 트래픽이 아직 낮아 상한 고정), 큐 100으로 스파이크 흡수한다.
		// 큐까지 찬 초과분은 AbortPolicy로 즉시 거부한다 — submit()이 접수 트랜잭션의 afterCommit
		// 콜백으로 요청(웹) 스레드에서 돌기 때문에, CallerRunsPolicy를 쓰면 그 웹 스레드가 큐 소진분을
		// 대신 처리하느라 블로킹되는 트레이드오프가 생긴다. 거부된 등록은 pending인 채로 남고
		// recoverStalePending()이 다음 배치에서 집어가므로 유실은 아니다(MonitoringRegistrationExecutor.submit
		// 참조). 롤링 배포(was-rolling-deploy) 중 SIGTERM이 와도 waitForTasksToCompleteOnShutdown+
		// awaitTerminationSeconds(15)로 진행 중이던 등록 확인은 마저 끝내고 종료한다.
		ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
		pool.setCorePoolSize(2);
		pool.setMaxPoolSize(2);
		pool.setQueueCapacity(100);
		pool.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		pool.setWaitForTasksToCompleteOnShutdown(true);
		pool.setAwaitTerminationSeconds(15);
		pool.setThreadNamePrefix("monitoring-registration-");
		pool.initialize();
		this.registrationTaskExecutor = pool;

		log.info("모니터링 통신 계층 활성 base-url={} (조회 풀 monitoring-ro, max 3 / 등록 실행기 풀 2)", baseUrl);
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

	/** 브랜드 조회 계층 — 레거시 조회와 같은 읽기 전용 풀(monitoring-ro)을 공유한다. */
	@Bean
	BrandReadRepository brandReadRepository() {
		return new BrandReadRepository(monitoringJdbc);
	}

	/**
	 * 등록 실행기 전용 스레드풀 — 이름을 명시 지정해 Spring Boot 기본 applicationTaskExecutor
	 * (동일 타입 ThreadPoolTaskExecutor)와 자동배선 충돌을 피한다. 소비자는
	 * MonitoringRegistrationExecutor(v1.monitoring, monitoring.enabled 조건부 동일 게이트)뿐이다.
	 */
	@Bean(name = "monitoringRegistrationTaskExecutor")
	TaskExecutor monitoringRegistrationTaskExecutor() {
		return registrationTaskExecutor;
	}

	@PreDestroy
	void close() {
		registrationTaskExecutor.shutdown();
		monitoringDataSource.close();
	}
}
