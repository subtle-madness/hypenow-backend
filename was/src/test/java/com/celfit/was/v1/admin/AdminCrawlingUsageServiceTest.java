package com.celfit.was.v1.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandCallDailyRow;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.MonitoringReadRepository.UserCallDailyRow;
import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.common.V1ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 크롤링 사용량 집계의 순수 로직 검증(2026-08-12 설계) — KST 경계(자정·월초)와 연결 기간 귀속을
 * 고정 Clock으로 못 박는다. UTC로 계산하는 버그가 들어오면 자정 전후 1초 테스트 쌍이 잡아낸다.
 * 실 DB 왕복·인가·PUT 반영은 AdminCrawlingUsageIntegrationTest가 커버.
 */
class AdminCrawlingUsageServiceTest {

	private static final long USER_ID = 7L;

	private final BrandLinkRepository links = mock(BrandLinkRepository.class);
	private final BrandReadRepository reads = mock(BrandReadRepository.class);
	private final MonitoringReadRepository monitoringReads = mock(MonitoringReadRepository.class);
	private final AppSettingRepository settings = mock(AppSettingRepository.class);

	@BeforeEach
	void setUp() {
		given(settings.findValue(AdminCrawlingUsageService.UNIT_PRICE_KEY))
				.willReturn(Optional.of("0.0006"));
	}

	private AdminCrawlingUsageService serviceAt(String utcInstant) {
		// 서비스가 KST로 재조정(clock.withZone)하므로 픽스처의 시간대는 무관하다 — UTC로 준다.
		return new AdminCrawlingUsageService(links, Optional.of(reads), Optional.of(monitoringReads),
				settings, Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC));
	}

	/** 활성 연결(해제 없음). accountType은 집계와 무관 — own 고정. */
	private static BrandLinkRow activeLink(long brandId, String createdAt) {
		return new BrandLinkRow(brandId, USER_ID, brandId, "brand" + brandId, "own",
				OffsetDateTime.parse(createdAt), null);
	}

	private static BrandLinkRow closedLink(long brandId, String createdAt, String deletedAt) {
		return new BrandLinkRow(brandId, USER_ID, brandId, "brand" + brandId, "own",
				OffsetDateTime.parse(createdAt), OffsetDateTime.parse(deletedAt));
	}

	private static BrandCallDailyRow row(long brandId, String calledOn, long calls) {
		return new BrandCallDailyRow(brandId, LocalDate.parse(calledOn), calls);
	}

	@Test
	void 연결이_없으면_전부_0이고_단가는_그대로_내려간다() {
		given(links.findAllByUser(USER_ID)).willReturn(List.of());

		AdminCrawlingUsage usage = serviceAt("2026-08-12T03:00:00Z").usageFor(USER_ID);

		assertThat(usage).isEqualTo(new AdminCrawlingUsage(0, 0, 0, new BigDecimal("0.0006")));
		then(reads).shouldHaveNoInteractions();
	}

	@Test
	void monitoring_비활성이면_집계는_0으로_폴백한다() {
		given(links.findAllByUser(USER_ID)).willReturn(List.of(activeLink(1, "2026-01-01T00:00:00+09:00")));
		AdminCrawlingUsageService service = new AdminCrawlingUsageService(links, Optional.empty(),
				Optional.empty(), settings, Clock.fixed(Instant.parse("2026-08-12T03:00:00Z"), ZoneOffset.UTC));

		assertThat(service.usageFor(USER_ID)).isEqualTo(AdminCrawlingUsage.empty(new BigDecimal("0.0006")));
	}

	@Test
	void 캠페인_콘텐츠_콜은_브랜드_몫에_합산된다() {
		// 브랜드 연결 없이 캠페인·콘텐츠 등록만 있는 유저(스크린샷 사례) — 타깃 몫만으로도 집계돼야 한다.
		given(links.findAllByUser(USER_ID)).willReturn(List.of());
		given(monitoringReads.findDailyCallCounts(USER_ID)).willReturn(List.of(
				new UserCallDailyRow(LocalDate.parse("2026-08-11"), 90),
				new UserCallDailyRow(LocalDate.parse("2026-08-12"), 4)));

		AdminCrawlingUsage targetOnly = serviceAt("2026-08-12T03:00:00Z").usageFor(USER_ID);
		assertThat(targetOnly.totalCalls()).isEqualTo(94);
		assertThat(targetOnly.monthCalls()).isEqualTo(94);
		assertThat(targetOnly.todayCalls()).isEqualTo(4);

		// 브랜드 몫이 같이 있으면 단순 합 — 두 파이프라인의 KST 경계 규칙이 동일하다.
		given(links.findAllByUser(USER_ID)).willReturn(List.of(activeLink(1, "2026-01-01T00:00:00+09:00")));
		given(reads.findDailyCallCounts(anyCollection())).willReturn(List.of(row(1, "2026-07-31", 100)));

		AdminCrawlingUsage merged = serviceAt("2026-08-12T03:00:00Z").usageFor(USER_ID);
		assertThat(merged.totalCalls()).isEqualTo(194);
		assertThat(merged.monthCalls()).isEqualTo(94);   // 07-31 브랜드 콜은 지난달 — 이번 달 제외
		assertThat(merged.todayCalls()).isEqualTo(4);
	}

	@Test
	void 오늘_경계는_KST_자정이다() {
		given(links.findAllByUser(USER_ID)).willReturn(List.of(activeLink(1, "2026-01-01T00:00:00+09:00")));
		given(reads.findDailyCallCounts(anyCollection()))
				.willReturn(List.of(row(1, "2026-08-12", 5), row(1, "2026-08-11", 7)));

		// 2026-08-11T15:00:00Z == KST 08-12 00:00 정각 — 오늘은 08-12다.
		AdminCrawlingUsage afterMidnight = serviceAt("2026-08-11T15:00:00Z").usageFor(USER_ID);
		assertThat(afterMidnight.todayCalls()).isEqualTo(5);
		assertThat(afterMidnight.monthCalls()).isEqualTo(12);
		assertThat(afterMidnight.totalCalls()).isEqualTo(12);

		// 1초 전 — KST로는 아직 08-11. UTC 기준으로 자르는 버그면 여기서 어긋난다.
		AdminCrawlingUsage beforeMidnight = serviceAt("2026-08-11T14:59:59Z").usageFor(USER_ID);
		assertThat(beforeMidnight.todayCalls()).isEqualTo(7);
		assertThat(beforeMidnight.monthCalls()).isEqualTo(7);   // 08-12 행은 아직 미래 — 이번 달 합산 제외
		assertThat(beforeMidnight.totalCalls()).isEqualTo(12);
	}

	@Test
	void 이번_달_경계는_KST_월초_0시다() {
		given(links.findAllByUser(USER_ID)).willReturn(List.of(activeLink(1, "2026-01-01T00:00:00+09:00")));
		given(reads.findDailyCallCounts(anyCollection()))
				.willReturn(List.of(row(1, "2026-07-31", 2241), row(1, "2026-08-01", 12)));

		// 2026-07-31T15:00:00Z == KST 08-01 00:00 정각 — 이번 달은 8월이다.
		AdminCrawlingUsage augustStart = serviceAt("2026-07-31T15:00:00Z").usageFor(USER_ID);
		assertThat(augustStart.monthCalls()).isEqualTo(12);
		assertThat(augustStart.todayCalls()).isEqualTo(12);
		assertThat(augustStart.totalCalls()).isEqualTo(2253);

		// 1초 전 — KST로는 아직 7월 31일.
		AdminCrawlingUsage julyEnd = serviceAt("2026-07-31T14:59:59Z").usageFor(USER_ID);
		assertThat(julyEnd.monthCalls()).isEqualTo(2241);
		assertThat(julyEnd.todayCalls()).isEqualTo(2241);
		assertThat(julyEnd.totalCalls()).isEqualTo(2253);
	}

	@Test
	void 연결_기간_밖의_콜은_귀속되지_않는다() {
		// 연결일 KST 08-10 00:10(UTC로는 08-09) — UTC 날짜로 기간을 자르는 버그면 08-09 행이 새어든다.
		given(links.findAllByUser(USER_ID)).willReturn(List.of(
				closedLink(1, "2026-08-10T00:10:00+09:00", "2026-08-12T00:30:00+09:00")));
		given(reads.findDailyCallCounts(anyCollection())).willReturn(List.of(
				row(1, "2026-08-09", 100),   // 연결 전 — 제외
				row(1, "2026-08-10", 10),    // 연결일 포함(등록 백필이 이날 돈다)
				row(1, "2026-08-12", 20),    // 해제일 포함
				row(1, "2026-08-13", 40)));  // 해제 후 — 제외

		AdminCrawlingUsage usage = serviceAt("2026-08-20T01:00:00Z").usageFor(USER_ID);

		assertThat(usage.totalCalls()).isEqualTo(30);
		assertThat(usage.monthCalls()).isEqualTo(30);
		assertThat(usage.todayCalls()).isZero();
	}

	@Test
	void 겹치는_연결_기간은_이중_계상하지_않는다() {
		given(links.findAllByUser(USER_ID)).willReturn(List.of(
				activeLink(1, "2026-08-01T00:00:00+09:00"),
				closedLink(1, "2026-08-05T00:00:00+09:00", "2026-08-10T00:00:00+09:00")));
		given(reads.findDailyCallCounts(anyCollection())).willReturn(List.of(row(1, "2026-08-06", 9)));

		assertThat(serviceAt("2026-08-20T01:00:00Z").usageFor(USER_ID).totalCalls()).isEqualTo(9);
	}

	@Test
	void 단가_수정은_음수와_누락을_거부한다() {
		AdminCrawlingUsageService service = serviceAt("2026-08-12T03:00:00Z");

		assertThatThrownBy(() -> service.updateUnitPrice(null)).isInstanceOf(V1ApiException.class);
		assertThatThrownBy(() -> service.updateUnitPrice(new BigDecimal("-0.001")))
				.isInstanceOf(V1ApiException.class);
		then(settings).shouldHaveNoInteractions();
	}

	@Test
	void 단가_수정은_평문_표기로_저장한다() {
		AdminCrawlingUsageService service = serviceAt("2026-08-12T03:00:00Z");

		BigDecimal saved = service.updateUnitPrice(new BigDecimal("2.5E-3"));

		assertThat(saved).isEqualByComparingTo("0.0025");
		then(settings).should().upsert(AdminCrawlingUsageService.UNIT_PRICE_KEY, "0.0025");
	}

	@Test
	void 저장된_단가가_숫자가_아니면_기본값으로_폴백한다() {
		given(settings.findValue(AdminCrawlingUsageService.UNIT_PRICE_KEY))
				.willReturn(Optional.of("잘못된값"));
		given(links.findAllByUser(anyLong())).willReturn(List.of());

		AdminCrawlingUsage usage = serviceAt("2026-08-12T03:00:00Z").usageFor(USER_ID);

		assertThat(usage.unitPriceUsd()).isEqualByComparingTo(AdminCrawlingUsageService.DEFAULT_UNIT_PRICE);
	}
}
