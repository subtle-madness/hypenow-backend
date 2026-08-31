package com.celfit.was.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.ContentCacheSeed;
import com.celfit.was.IntegrationTest;
import com.celfit.was.v1.content.V1ContentPageService;
import com.celfit.was.v1.content.V1ContentQuery;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Redis 불능(닫힌 포트) = 캐시 미스로 강등 — 조회는 DB 직행으로 정상(스펙 §7 fail-open).
 *  @Cacheable(sync=true) 경로까지 CacheErrorHandler가 덮는지가 핵심(플레인 캐시 미스와 달리
 *  별도 코드 경로 RedisCacheWriter.get(name, key, loader, ttl, tti)를 타므로 별도 검증 가치가 있다). */
class CacheFailOpenIntegrationTest extends IntegrationTest {

	@DynamicPropertySource
	static void deadRedis(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.port", () -> 1); // 닫힌 포트 — 연결 즉시 거부
		registry.add("spring.data.redis.timeout", () -> "200ms");
		registry.add("spring.data.redis.connect-timeout", () -> "200ms");
	}

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	V1ContentPageService pageService;

	@Test
	void 레디스_불능이어도_조회는_정상() {
		ContentCacheSeed.reset(jdbcTemplate);
		var page = pageService.page(V1ContentQuery.of(LocalDate.now().minusDays(7), LocalDate.now(),
				null, null, null, null, null, null, null, null, null, 100, 0, null));
		assertThat(page.total()).isEqualTo(2);
	}
}
