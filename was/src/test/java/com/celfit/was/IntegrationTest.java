package com.celfit.was;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 통합 테스트 공통 베이스. Postgres 컨테이너 1개를 JVM 전체에서 공유(싱글턴 패턴).
 *
 * <p>redis 포트는 닫힌 포트(1)로 핀 고정 — 캐시 미사용 통합 테스트가 로컬 dev redis(6379)를
 * 읽고 쓰는 것을 차단한다(fail-open이라 조회는 DB 직행으로 정상 — CacheFailOpenIntegrationTest 패턴).
 * 반드시 인라인 properties로 핀한다: @DynamicPropertySource로 핀하면 부모 등록이 자식보다 나중에
 * 적용돼 CacheIntegrationTest의 전용 컨테이너 포트를 되덮는 것이 실측됐다(2026-07-29). 인라인
 * 프로퍼티는 동적 프로퍼티보다 우선순위가 낮아 실 Redis가 필요한 하위 클래스가 자체
 * @DynamicPropertySource로 안전하게 덮는다.
 */
@SpringBootTest(properties = "spring.data.redis.port=1")
public abstract class IntegrationTest {

	// 컨텍스트 캐시에 살아있는 컨텍스트마다 Hikari 풀(기본 10)이 이 컨테이너 하나에 붙는다 —
	// 캐시·모니터링 컨텍스트 합류로 기본 max_connections(100)이 소진돼("too many clients already")
	// 상한을 올리고, 테스트 풀도 아래에서 축소한다.
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer("postgres:17-alpine")
					.withCommand("postgres", "-c", "max_connections=300");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		// 프리페치(비동기 조회) 동시성까지 감안한 최소 풀 — 컨텍스트당 10개 점유가 근본 원인
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
	}
}
