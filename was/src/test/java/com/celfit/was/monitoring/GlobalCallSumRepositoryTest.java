package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.sql.Connection;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * 모니터링 콜의 전역(전 브랜드·전 유저) 날짜별 합계 검증(설계 2026-08-13 §3-4).
 * 유저별 카드(AdminCrawlingUsageService)와 달리 연결 기간으로 자르지 않는다 — 공유 브랜드가
 * 유저마다 계상되는 이중 계상을 피하려면 브랜드 축에서 직접 합산해야 하기 때문이다.
 */
@TestPropertySource(properties = {"monitoring.enabled=true", "monitoring.digest.cron=-",
		"monitoring.digest.catchup-cron=-", "monitoring.recover.cron=-"})
class GlobalCallSumRepositoryTest extends IntegrationTest {

	@DynamicPropertySource
	static void monitoringDatasource(DynamicPropertyRegistry registry) {
		registry.add("monitoring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("monitoring.datasource.username", POSTGRES::getUsername);
		registry.add("monitoring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired
	BrandReadRepository brandReads;
	@Autowired
	MonitoringReadRepository monitoringReads;
	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	DataSource dataSource;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
		}
		jdbcClient.sql("TRUNCATE brand_call_count").update();
		jdbcClient.sql("TRUNCATE target_call_count").update();
	}

	@Test
	void 브랜드_콜은_전_브랜드가_날짜별로_합산된다() {
		jdbcClient.sql("""
				INSERT INTO brand_call_count VALUES
				 (1, date '2026-08-13', 10), (2, date '2026-08-13', 5), (3, date '2026-08-12', 7)
				""").update();

		assertThat(brandReads.sumDailyCallCounts()).containsExactlyInAnyOrder(
				new DailyCallSum(LocalDate.of(2026, 8, 13), 15),
				new DailyCallSum(LocalDate.of(2026, 8, 12), 7));
	}

	@Test
	void 캠페인_콜은_전_유저가_날짜별로_합산된다() {
		jdbcClient.sql("""
				INSERT INTO target_call_count VALUES
				 (100, date '2026-08-13', 3), (200, date '2026-08-13', 4), (100, date '2026-08-11', 9)
				""").update();

		assertThat(monitoringReads.sumDailyCallCounts()).containsExactlyInAnyOrder(
				new DailyCallSum(LocalDate.of(2026, 8, 13), 7),
				new DailyCallSum(LocalDate.of(2026, 8, 11), 9));
	}

	@Test
	void 행이_없으면_빈_목록이다() {
		assertThat(brandReads.sumDailyCallCounts()).isEmpty();
		assertThat(monitoringReads.sumDailyCallCounts()).isEmpty();
	}
}
