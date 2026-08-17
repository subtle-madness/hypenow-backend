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

/**
 * 모니터링 콜의 전역(전 브랜드·전 유저) 날짜별 합계 검증(설계 2026-08-13 §3-4).
 * 유저별 카드(AdminCrawlingUsageService)와 달리 연결 기간으로 자르지 않는다 — 공유 브랜드가
 * 유저마다 계상되는 이중 계상을 피하려면 브랜드 축에서 직접 합산해야 하기 때문이다.
 *
 * <p>형제 테스트(BrandReadRepositoryTest·MonitoringReadRepositoryTest)와 같은 관용구 —
 * 전체 컨텍스트 DataSource에 픽스처를 적용하고 리포지토리를 직접 생성한다. monitoring 전용
 * 데이터소스 배선(monitoring.enabled)은 조회 SQL 검증에 필요 없고, 전용 컨텍스트 하나를 더
 * 띄워 공유 컨테이너의 커넥션 예산만 먹는다.
 */
class GlobalCallSumRepositoryTest extends IntegrationTest {

	@Autowired
	DataSource dataSource;

	JdbcClient jdbc;
	BrandReadRepository brandReads;
	MonitoringReadRepository monitoringReads;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
		}
		jdbc = JdbcClient.create(dataSource);
		jdbc.sql("TRUNCATE brand_call_count").update();
		jdbc.sql("TRUNCATE target_call_count").update();
		brandReads = new BrandReadRepository(jdbc);
		monitoringReads = new MonitoringReadRepository(jdbc);
	}

	@Test
	void 브랜드_콜은_전_브랜드가_날짜별로_합산된다() {
		jdbc.sql("""
				INSERT INTO brand_call_count VALUES
				 (1, date '2026-08-13', 10), (2, date '2026-08-13', 5), (3, date '2026-08-12', 7)
				""").update();

		assertThat(brandReads.sumDailyCallCounts()).containsExactlyInAnyOrder(
				new DailyCallSum(LocalDate.of(2026, 8, 13), 15),
				new DailyCallSum(LocalDate.of(2026, 8, 12), 7));
	}

	@Test
	void 캠페인_콜은_전_유저가_날짜별로_합산된다() {
		jdbc.sql("""
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
