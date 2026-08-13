package com.celfit.was.v1.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.celfit.contract.analysis.CrawlCallDaily;
import com.celfit.was.crawlcost.CrawlCallDailyRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.DailyCallSum;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.admin.AdminCrawlingCostSummary.Segment;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 전역 크롤링 비용 합산의 순수 로직 검증(설계 2026-08-13) — KST 경계(자정·월초), 세 소스 합산,
 * 잡 라벨 매핑, 열화(모니터링 비활성·조회 예외) 규칙을 고정 Clock으로 못 박는다.
 * 실 DB 왕복·인가는 AdminCrawlingCostSummaryIntegrationTest가 커버.
 */
class AdminCrawlingCostSummaryServiceTest {

	private final BrandReadRepository brandReads = mock(BrandReadRepository.class);
	private final MonitoringReadRepository monitoringReads = mock(MonitoringReadRepository.class);
	private final CrawlCallDailyRepository crawlReads = mock(CrawlCallDailyRepository.class);
	private final AppSettingRepository settings = mock(AppSettingRepository.class);

	@BeforeEach
	void setUp() {
		given(settings.findValue(AdminCrawlingUsageService.UNIT_PRICE_KEY))
				.willReturn(Optional.of("0.0006"));
		given(brandReads.sumDailyCallCounts()).willReturn(List.of());
		given(monitoringReads.sumDailyCallCounts()).willReturn(List.of());
		given(crawlReads.findAll()).willReturn(List.of());
	}

	/** 서비스가 KST로 재조정(clock.withZone)하므로 픽스처 시간대는 UTC로 준다. */
	private AdminCrawlingCostSummaryService serviceAt(String utcInstant) {
		return new AdminCrawlingCostSummaryService(Optional.of(brandReads), Optional.of(monitoringReads),
				crawlReads, settings, Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC));
	}

	private static Segment segment(List<Segment> breakdown, String key) {
		return breakdown.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
	}

	@Test
	void 데이터가_없어도_고정_7행과_0을_돌려준다() {
		AdminCrawlingCostSummary summary = serviceAt("2026-08-13T01:00:00Z").summary();

		assertThat(summary.breakdown()).extracting(Segment::key).containsExactly(
				"BRAND_MONITORING", "CAMPAIGN_MONITORING", "CRAWLER_DISCOVER", "CRAWLER_QUALIFY",
				"CRAWLER_COLLECT", "CRAWLER_SIMILAR", "CRAWLER_REELS");
		assertThat(summary.totals().totalCalls()).isZero();
		assertThat(summary.totals().totalCostUsd()).isEqualByComparingTo("0");
		assertThat(summary.unitPriceUsd()).isEqualByComparingTo("0.0006");
	}

	@Test
	void 세_소스를_KST_구간별로_합산하고_단가를_곱한다() {
		// 고정 시각 2026-08-13 10:00 KST → 오늘=08-13, 이번 달 시작=08-01.
		given(brandReads.sumDailyCallCounts()).willReturn(List.of(
				new DailyCallSum(LocalDate.of(2026, 8, 13), 10),    // 오늘·이달·전체
				new DailyCallSum(LocalDate.of(2026, 8, 1), 90),     // 이달·전체
				new DailyCallSum(LocalDate.of(2026, 7, 31), 400))); // 전체만
		given(monitoringReads.sumDailyCallCounts()).willReturn(List.of(
				new DailyCallSum(LocalDate.of(2026, 8, 13), 5)));
		given(crawlReads.findAll()).willReturn(List.of(
				new CrawlCallDaily("COLLECT", LocalDate.of(2026, 8, 13), 100),
				new CrawlCallDaily("REELS", LocalDate.of(2026, 7, 20), 1000)));

		AdminCrawlingCostSummary summary = serviceAt("2026-08-13T01:00:00Z").summary();

		Segment brand = segment(summary.breakdown(), "BRAND_MONITORING");
		assertThat(brand.totalCalls()).isEqualTo(500);
		assertThat(brand.monthCalls()).isEqualTo(100);
		assertThat(brand.todayCalls()).isEqualTo(10);
		assertThat(segment(summary.breakdown(), "CRAWLER_COLLECT").todayCalls()).isEqualTo(100);
		assertThat(segment(summary.breakdown(), "CRAWLER_REELS").monthCalls()).isZero();
		assertThat(segment(summary.breakdown(), "CRAWLER_REELS").totalCalls()).isEqualTo(1000);

		// totals = breakdown 합: 전체 500+5+100+1000 = 1605, 이달 100+5+100 = 205, 오늘 10+5+100 = 115.
		assertThat(summary.totals().totalCalls()).isEqualTo(1605);
		assertThat(summary.totals().monthCalls()).isEqualTo(205);
		assertThat(summary.totals().todayCalls()).isEqualTo(115);
		// 비용은 반올림 없이 곱셈 결과 그대로 — 1605 × 0.0006 = 0.9630.
		assertThat(summary.totals().totalCostUsd()).isEqualByComparingTo("0.9630");
	}

	@Test
	void KST_자정_경계가_오늘과_어제를_가른다() {
		given(crawlReads.findAll()).willReturn(List.of(
				new CrawlCallDaily("COLLECT", LocalDate.of(2026, 8, 13), 7)));

		// 2026-08-13 23:59:59 KST — 아직 08-13.
		assertThat(segment(serviceAt("2026-08-13T14:59:59Z").summary().breakdown(), "CRAWLER_COLLECT")
				.todayCalls()).isEqualTo(7);
		// 2초 뒤 = 08-14 00:00:01 KST — 어제 몫이 되어 today에서 빠진다.
		assertThat(segment(serviceAt("2026-08-13T15:00:01Z").summary().breakdown(), "CRAWLER_COLLECT")
				.todayCalls()).isZero();
	}

	@Test
	void KST_월초_경계가_이번_달과_지난달을_가른다() {
		given(crawlReads.findAll()).willReturn(List.of(
				new CrawlCallDaily("COLLECT", LocalDate.of(2026, 8, 1), 7)));

		// 2026-08-31 23:59:59 KST — 8월분이라 month에 든다.
		assertThat(segment(serviceAt("2026-08-31T14:59:59Z").summary().breakdown(), "CRAWLER_COLLECT")
				.monthCalls()).isEqualTo(7);
		// 2초 뒤 = 09-01 00:00:01 KST — 지난달이 되어 빠진다.
		assertThat(segment(serviceAt("2026-08-31T15:00:01Z").summary().breakdown(), "CRAWLER_COLLECT")
				.monthCalls()).isZero();
	}

	@Test
	void 매핑에_없는_잡도_코드명으로_노출된다() {
		given(crawlReads.findAll()).willReturn(List.of(
				new CrawlCallDaily("NEWJOB", LocalDate.of(2026, 8, 13), 42)));

		AdminCrawlingCostSummary summary = serviceAt("2026-08-13T01:00:00Z").summary();

		Segment unknown = segment(summary.breakdown(), "CRAWLER_NEWJOB");
		assertThat(unknown.label()).isEqualTo("NEWJOB");
		assertThat(unknown.totalCalls()).isEqualTo(42);
		assertThat(summary.totals().totalCalls()).isEqualTo(42);   // 합계에서 삼켜지지 않는다
	}

	@Test
	void 모니터링_비활성이면_열화_표시하고_크롤러_몫은_그대로_낸다() {
		given(crawlReads.findAll()).willReturn(List.of(
				new CrawlCallDaily("COLLECT", LocalDate.of(2026, 8, 13), 3)));
		AdminCrawlingCostSummaryService service = new AdminCrawlingCostSummaryService(
				Optional.empty(), Optional.empty(), crawlReads, settings,
				Clock.fixed(Instant.parse("2026-08-13T01:00:00Z"), ZoneOffset.UTC));

		AdminCrawlingCostSummary summary = service.summary();

		assertThat(summary.sources()).anySatisfy(s -> {
			assertThat(s.key()).isEqualTo("MONITORING");
			assertThat(s.available()).isFalse();
			assertThat(s.latestCallOn()).isNull();
		});
		assertThat(segment(summary.breakdown(), "BRAND_MONITORING").totalCalls()).isZero();
		assertThat(segment(summary.breakdown(), "CRAWLER_COLLECT").totalCalls()).isEqualTo(3);
	}

	@Test
	void 모니터링_조회가_터져도_500이_아니라_열화로_접는다() {
		given(brandReads.sumDailyCallCounts())
				.willThrow(new DataAccessResourceFailureException("monitoring DB 불통"));

		AdminCrawlingCostSummary summary = serviceAt("2026-08-13T01:00:00Z").summary();

		assertThat(summary.sources()).anySatisfy(s -> {
			assertThat(s.key()).isEqualTo("MONITORING");
			assertThat(s.available()).isFalse();
		});
		assertThat(summary.totals().totalCalls()).isZero();
	}

	@Test
	void 소스별_최신_날짜를_신선도로_노출한다() {
		given(brandReads.sumDailyCallCounts()).willReturn(List.of(
				new DailyCallSum(LocalDate.of(2026, 8, 11), 1),
				new DailyCallSum(LocalDate.of(2026, 8, 13), 1)));
		given(crawlReads.findAll()).willReturn(List.of(
				new CrawlCallDaily("COLLECT", LocalDate.of(2026, 8, 10), 1)));

		AdminCrawlingCostSummary summary = serviceAt("2026-08-13T01:00:00Z").summary();

		assertThat(summary.sources()).anySatisfy(s -> {
			assertThat(s.key()).isEqualTo("MONITORING");
			assertThat(s.latestCallOn()).isEqualTo(LocalDate.of(2026, 8, 13));
		});
		assertThat(summary.sources()).anySatisfy(s -> {
			assertThat(s.key()).isEqualTo("CRAWLER");
			assertThat(s.available()).isTrue();
			assertThat(s.latestCallOn()).isEqualTo(LocalDate.of(2026, 8, 10));
		});
	}

	@Test
	void 단가가_숫자가_아니면_기본값으로_폴백한다() {
		given(settings.findValue(AdminCrawlingUsageService.UNIT_PRICE_KEY))
				.willReturn(Optional.of("abc"));

		assertThat(serviceAt("2026-08-13T01:00:00Z").summary().unitPriceUsd())
				.isEqualByComparingTo(AdminCrawlingUsageService.DEFAULT_UNIT_PRICE);
	}
}
