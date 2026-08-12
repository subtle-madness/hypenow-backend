package com.celfit.was.v1.admin;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandCallDailyRow;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.MonitoringReadRepository.UserCallDailyRow;
import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 어드민 크롤링 사용량·전역 단가(2026-08-12 프론트 요청서 + 같은 날 설계·비용 범위 확장) —
 * 브랜드 태그 모니터링 콜과 캠페인·콘텐츠 모니터링 콜을 합쳐 세 구간(전체·이번 달·오늘,
 * 전부 KST 경계)을 합산한다.
 *
 * <p><b>브랜드 몫</b>은 유저의 브랜드 연결(app.brand_monitorings, 해제분 포함)에 monitoring의
 * 브랜드별 일별 콜 집계(brand_call_count)를 물려 <b>연결 기간</b> 기준으로 자른다: 콜의 KST
 * 달력일이 그 유저의 어느 연결 기간(연결일~해제일, 양끝 포함)에라도 들면 그 유저 몫이다. 연결 전
 * 이력(같은 브랜드를 먼저 쓰던 다른 유저의 콜)을 물려받지 않고, 해제 후 콜(다른 유저 몫으로 계속
 * 도는 수집)을 떠안지 않는다. 같은 날 여러 연결이 겹쳐도 행 단위 포함 판정이라 이중 계상은 없다.
 * 같은 브랜드를 여러 유저가 공유하면 그 기간의 콜은 양쪽 모두에 계상된다(비용 관점 상한 — 계약
 * 문서에 명시).
 *
 * <p><b>캠페인·콘텐츠 몫</b>(2026-08-12 범위 확장)은 monitoring이 콜 시점에 유저 귀속을 끝낸
 * target_call_count(유저 키)를 그대로 더한다 — target.user_id가 등록 필수값이라 가능했고, 기간
 * 계산이 여기 없다. 계정 열거처럼 한 콜이 여러 유저의 캠페인을 서빙하면 유저마다 계상된다
 * (브랜드 공유와 같은 상한 관점).
 *
 * <p>합산은 Java에서 한다: 링크 기간은 app 스키마, 콜 집계는 monitoring DB라 SQL 조인이 불가능
 * (크로스 DB 조인 금지 원칙과도 정합)하고, 행 수가 브랜드·유저당 하루 1행이라 전량 조회가 싸다.
 */
@Service
public class AdminCrawlingUsageService {

	static final String UNIT_PRICE_KEY = "crawling.unit-price-usd";
	/** 시드(V20260812100500)와 같은 값 — 행 유실 등 이상 상황의 폴백일 뿐 정본은 app_setting. */
	static final BigDecimal DEFAULT_UNIT_PRICE = new BigDecimal("0.0006");

	private static final Logger log = LoggerFactory.getLogger(AdminCrawlingUsageService.class);

	private final BrandLinkRepository linkRepository;
	private final Optional<BrandReadRepository> brandReads;
	private final Optional<MonitoringReadRepository> monitoringReads;
	private final AppSettingRepository settings;
	private final Clock clock;

	public AdminCrawlingUsageService(BrandLinkRepository linkRepository,
			Optional<BrandReadRepository> brandReads, Optional<MonitoringReadRepository> monitoringReads,
			AppSettingRepository settings, Clock clock) {
		this.linkRepository = linkRepository;
		this.brandReads = brandReads;   // monitoring.enabled=false면 비어 있다 — 집계는 전부 0
		this.monitoringReads = monitoringReads;
		this.settings = settings;
		this.clock = clock;
	}

	/** 유저별 사용량 — 연결·이력이 없어도 200 + 0(404는 프론트가 "미배포"로 해석하는 예약 신호). */
	public AdminCrawlingUsage usageFor(long userId) {
		BigDecimal unitPrice = currentUnitPrice();
		LocalDate today = LocalDate.now(clock.withZone(KstTimestamps.KST));
		LocalDate monthStart = today.withDayOfMonth(1);
		PeriodSums sums = new PeriodSums(today, monthStart);
		sumBrandCalls(userId, sums);
		sumTargetCalls(userId, sums);
		return new AdminCrawlingUsage(sums.total, sums.month, sums.day, unitPrice);
	}

	/** 브랜드 태그 모니터링 몫 — 연결 기간(클래스 문서 참조)으로 잘라 합산한다. */
	private void sumBrandCalls(long userId, PeriodSums sums) {
		if (brandReads.isEmpty()) {
			return;
		}
		List<BrandLinkRow> links = linkRepository.findAllByUser(userId);
		if (links.isEmpty()) {
			return;
		}
		Map<Long, List<LinkPeriod>> periodsByBrand = new LinkedHashMap<>();
		for (BrandLinkRow link : links) {
			periodsByBrand.computeIfAbsent(link.brandId(), k -> new ArrayList<>())
					.add(LinkPeriod.of(link));
		}
		for (BrandCallDailyRow row : brandReads.get().findDailyCallCounts(periodsByBrand.keySet())) {
			boolean attributed = periodsByBrand.get(row.brandId()).stream()
					.anyMatch(p -> p.contains(row.calledOn()));
			if (attributed) {
				sums.add(row.calledOn(), row.calls());
			}
		}
	}

	/** 캠페인·콘텐츠 모니터링 몫 — 이미 유저 키로 귀속된 집계라 그대로 더한다. */
	private void sumTargetCalls(long userId, PeriodSums sums) {
		if (monitoringReads.isEmpty()) {
			return;
		}
		for (UserCallDailyRow row : monitoringReads.get().findDailyCallCounts(userId)) {
			sums.add(row.calledOn(), row.calls());
		}
	}

	/** 세 구간(전체·이번 달·오늘) 누적기 — 미래 날짜 행(이론상)은 month·day에서 제외된다. */
	private static final class PeriodSums {
		private final LocalDate today;
		private final LocalDate monthStart;
		private long total;
		private long month;
		private long day;

		private PeriodSums(LocalDate today, LocalDate monthStart) {
			this.today = today;
			this.monthStart = monthStart;
		}

		private void add(LocalDate calledOn, long calls) {
			total += calls;
			if (!calledOn.isBefore(monthStart) && !calledOn.isAfter(today)) {
				month += calls;
			}
			if (calledOn.equals(today)) {
				day += calls;
			}
		}
	}

	/**
	 * 전역 단가 수정(PUT §2-2) — 0 이상의 유한한 숫자만. Jackson이 BigDecimal로 파싱한 시점에
	 * NaN·Infinity·비숫자는 이미 본문 파싱 400으로 걸러졌으므로 여기선 누락·음수만 판정한다.
	 * 반환은 저장된 값(다음 GET과 동일 값 보장).
	 */
	public BigDecimal updateUnitPrice(BigDecimal unitPriceUsd) {
		if (unitPriceUsd == null || unitPriceUsd.signum() < 0) {
			throw V1ApiException.validation("unitPriceUsd는 0 이상의 숫자여야 해요.");
		}
		settings.upsert(UNIT_PRICE_KEY, unitPriceUsd.toPlainString());
		return unitPriceUsd;
	}

	public BigDecimal currentUnitPrice() {
		Optional<String> stored = settings.findValue(UNIT_PRICE_KEY);
		if (stored.isEmpty()) {
			return DEFAULT_UNIT_PRICE;   // 시드 이전·행 유실 폴백 — PUT이 한 번이라도 오면 행이 생긴다
		}
		try {
			return new BigDecimal(stored.get());
		} catch (NumberFormatException e) {
			// 수동 SQL로만 만들 수 있는 상태 — 카드 전체를 장애로 만드는 대신 폴백하고 흔적을 남긴다.
			log.warn("crawling.unit-price-usd 값이 숫자가 아님({}) — 기본값 폴백", stored.get());
			return DEFAULT_UNIT_PRICE;
		}
	}

	/** 연결 1건의 귀속 기간 — KST 달력일, 양끝 포함(연결일의 등록 백필·해제일까지의 콜은 유저 몫). */
	private record LinkPeriod(LocalDate start, LocalDate end) {

		static LinkPeriod of(BrandLinkRow link) {
			return new LinkPeriod(KstTimestamps.toKstDate(link.createdAt()),
					KstTimestamps.toKstDate(link.deletedAt()));   // 활성 연결은 end=null(무기한)
		}

		boolean contains(LocalDate day) {
			return !day.isBefore(start) && (end == null || !day.isAfter(end));
		}
	}
}
