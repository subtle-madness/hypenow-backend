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

	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}
}
