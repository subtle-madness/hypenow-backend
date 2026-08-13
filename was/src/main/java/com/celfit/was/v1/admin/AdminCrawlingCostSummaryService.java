package com.celfit.was.v1.admin;

import com.celfit.contract.analysis.CrawlCallDaily;
import com.celfit.was.crawlcost.CrawlCallDailyRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.DailyCallSum;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.v1.admin.AdminCrawlingCostSummary.Segment;
import com.celfit.was.v1.admin.AdminCrawlingCostSummary.SourceStatus;
import com.celfit.was.v1.admin.AdminCrawlingCostSummary.Totals;
import com.celfit.was.v1.common.KstTimestamps;
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
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 어드민 전역 크롤링 비용 집계(설계 2026-08-13) — 서비스 전체가 크롤링에 쓴 돈을 세 구간
 * (전체·이번 달·오늘, KST)과 파이프라인별로 분해한다.
 *
 * <p><b>모니터링 몫</b>은 brand_call_count·target_call_count를 <b>원본 축에서 직접</b> 합산한다.
 * 유저별 카드({@link AdminCrawlingUsageService})처럼 연결 기간으로 자른 값을 더하면, 공유
 * 브랜드가 유저마다 계상돼 실제 지출보다 큰 수가 나온다. 이 차이는 계약 문서에 명시돼 있다.
 *
 * <p><b>크롤러 몫</b>은 analytics 미러가 채우는 crawl_call_daily를 읽는다 — was는 raw DB에
 * 접근할 수 없고(시스템 경계), 모듈 간 HTTP도 쓰지 않는다(ARCHITECTURE §2). 대가는 신선도로,
 * 미러 주기(04:30 KST) 이후의 콜은 다음 미러까지 보이지 않는다 —
 * {@code sources[].latestCallOn}이 그 지연을 드러낸다.
 *
 * <p><b>404를 내지 않고, 못 읽은 "구간"은 500 대신 열화로 접는다</b>: 소스 1종의 조회가 터져도
 * available=false로 표시하고 그 구간 집계를 0으로 둔다 — 비용 관측이 어드민 화면을 통째로 죽이면
 * 안 된다. <b>다만 "어떤 경우에도 500이 없다"는 뜻은 아니다</b>: 단가 조회(app.app_setting)는
 * 구간 열화의 대상이 아니라 응답 조립의 전제라 try/catch 밖에 있고, 그래서 <b>기본 데이터소스
 * 자체가 불통이면 이 API도 500이 된다</b>(세션이 Redis에 있어 DB가 죽어도 인증된 요청은 여기까지
 * 도달한다). 08-12 유저별 카드도 같은 모양이며, 이 경계는 의도된 것이다 — DB 전면 불통은 어드민의
 * 다른 모든 표면과 함께 죽는 장애이지 이 화면만의 열화 사유가 아니다.
 */
@Service
public class AdminCrawlingCostSummaryService {

	private static final Logger log = LoggerFactory.getLogger(AdminCrawlingCostSummaryService.class);

	private static final String BRAND_KEY = "BRAND_MONITORING";
	private static final String CAMPAIGN_KEY = "CAMPAIGN_MONITORING";
	private static final String CRAWLER_PREFIX = "CRAWLER_";

	/** 표시 순서 고정 + 데이터가 없어도 행을 유지하기 위한 골격(설계 §2). */
	private static final List<String> BASE_KEYS = List.of(BRAND_KEY, CAMPAIGN_KEY,
			"CRAWLER_DISCOVER", "CRAWLER_QUALIFY", "CRAWLER_COLLECT", "CRAWLER_SIMILAR", "CRAWLER_REELS");

	private static final Map<String, String> LABELS = Map.of(
			BRAND_KEY, "브랜드 태그 모니터링",
			CAMPAIGN_KEY, "캠페인·콘텐츠 모니터링",
			"CRAWLER_DISCOVER", "해시태그 발굴",
			"CRAWLER_QUALIFY", "프로필 판정",
			"CRAWLER_COLLECT", "게시물 수집",
			"CRAWLER_SIMILAR", "유사 계정 발굴",
			"CRAWLER_REELS", "릴스 수집");

	private final Optional<BrandReadRepository> brandReads;
	private final Optional<MonitoringReadRepository> monitoringReads;
	private final CrawlCallDailyRepository crawlReads;
	private final AdminCrawlingUsageService usageService;
	private final Clock clock;

	public AdminCrawlingCostSummaryService(Optional<BrandReadRepository> brandReads,
			Optional<MonitoringReadRepository> monitoringReads, CrawlCallDailyRepository crawlReads,
			AdminCrawlingUsageService usageService, Clock clock) {
		this.brandReads = brandReads;   // monitoring.enabled=false면 비어 있다 — 모니터링 구간은 열화
		this.monitoringReads = monitoringReads;
		this.crawlReads = crawlReads;
		// 단가는 유저별 카드의 리더를 그대로 재사용한다 — 키만 공유하고 읽기를 포크하면
		// "두 화면의 단가가 갈라질 수 없다"는 보장이 폴백·반올림이 갈리는 순간 깨진다.
		this.usageService = usageService;
		this.clock = clock;
	}

	public AdminCrawlingCostSummary summary() {
		BigDecimal unitPrice = usageService.currentUnitPrice();
		LocalDate today = LocalDate.now(clock.withZone(KstTimestamps.KST));
		LocalDate monthStart = today.withDayOfMonth(1);

		Map<String, PeriodSums> sums = new LinkedHashMap<>();
		for (String key : BASE_KEYS) {
			sums.put(key, new PeriodSums(today, monthStart));
		}

		SourceRead monitoring = readMonitoring(sums, today, monthStart);
		SourceRead crawler = readCrawler(sums, today, monthStart);

		List<Segment> breakdown = new ArrayList<>();
		PeriodSums totals = new PeriodSums(today, monthStart);
		for (Map.Entry<String, PeriodSums> entry : sums.entrySet()) {
			PeriodSums s = entry.getValue();
			breakdown.add(new Segment(entry.getKey(), LABELS.getOrDefault(entry.getKey(), fallbackLabel(entry.getKey())),
					s.total(), s.month(), s.day(),
					cost(unitPrice, s.total()), cost(unitPrice, s.month()), cost(unitPrice, s.day())));
			// 총계는 구간 값을 그대로 더한다 — 날짜 재판정 없이 breakdown과 항상 일치시키기 위해.
			totals.addPreAggregated(s.total(), s.month(), s.day());
		}

		return new AdminCrawlingCostSummary(
				new Totals(totals.total(), totals.month(), totals.day(),
						cost(unitPrice, totals.total()), cost(unitPrice, totals.month()),
						cost(unitPrice, totals.day())),
				breakdown, unitPrice,
				List.of(new SourceStatus("MONITORING", monitoring.available(), monitoring.latestCallOn()),
						new SourceStatus("CRAWLER", crawler.available(), crawler.latestCallOn())));
	}

	/**
	 * 모니터링 두 파이프라인을 읽어 누적한다. monitoring.enabled=false(로컬 기본)거나 조회가
	 * 터지면 열화로 접는다 — 부가 서브시스템의 불능이 어드민 비용 화면 전체를 죽이면 안 된다.
	 */
	private SourceRead readMonitoring(Map<String, PeriodSums> sums, LocalDate today, LocalDate monthStart) {
		if (brandReads.isEmpty() || monitoringReads.isEmpty()) {
			return new SourceRead(false, null);
		}
		try {
			LocalDate latest = null;
			for (DailyCallSum row : brandReads.get().sumDailyCallCounts()) {
				sums.get(BRAND_KEY).add(row.calledOn(), row.calls());
				latest = later(latest, row.calledOn());
			}
			for (DailyCallSum row : monitoringReads.get().sumDailyCallCounts()) {
				sums.get(CAMPAIGN_KEY).add(row.calledOn(), row.calls());
				latest = later(latest, row.calledOn());
			}
			return new SourceRead(true, latest);
		} catch (DataAccessException e) {
			log.warn("모니터링 콜 집계 조회 실패 — 해당 구간을 열화 표시한다", e);
			// 부분 누적분을 남기면 "0인지 일부인지" 알 수 없는 수가 나간다 — 0으로 되돌린다.
			sums.put(BRAND_KEY, new PeriodSums(today, monthStart));
			sums.put(CAMPAIGN_KEY, new PeriodSums(today, monthStart));
			return new SourceRead(false, null);
		}
	}

	/**
	 * 크롤러 미러를 읽어 잡별로 누적한다. 매핑에 없는 잡도 CRAWLER_&lt;JOB&gt; 구간을 새로 만들어
	 * 노출한다 — 매핑 누락이 비용을 조용히 삼키면 안 된다.
	 *
	 * <p>모니터링과 <b>같은 열화 경로</b>를 탄다. crawl_call_daily의 마이그레이션은 was가 아니라
	 * analytics 소관이라(V20260813105711), analytics가 그걸 적용하기 전에 was가 뜨거나 analytics를
	 * 롤백하면 데이터소스는 멀쩡한데 "relation does not exist"가 난다 — 이 배포 스큐로 어드민
	 * 비용 화면이 500이 되면 안 된다.
	 */
	private SourceRead readCrawler(Map<String, PeriodSums> sums, LocalDate today, LocalDate monthStart) {
		try {
			LocalDate latest = null;
			for (CrawlCallDaily row : crawlReads.findAll()) {
				String key = CRAWLER_PREFIX + row.job();
				sums.computeIfAbsent(key, k -> new PeriodSums(today, monthStart)).add(row.calledOn(), row.calls());
				latest = later(latest, row.calledOn());
			}
			return new SourceRead(true, latest);
		} catch (DataAccessException e) {
			log.warn("크롤러 콜 집계 조회 실패 — 해당 구간을 열화 표시한다", e);
			// 크롤러 구간만 0으로 되돌린다 — 모니터링은 별개 소스라 이미 읽은 값을 살린다.
			// 골격에 없는 잡 키는 이 조회가 만든 것이므로 통째로 지운다(0인 유령 행을 남기지 않는다).
			sums.keySet().removeIf(key -> key.startsWith(CRAWLER_PREFIX) && !BASE_KEYS.contains(key));
			for (String key : BASE_KEYS) {
				if (key.startsWith(CRAWLER_PREFIX)) {
					sums.put(key, new PeriodSums(today, monthStart));
				}
			}
			return new SourceRead(false, null);
		}
	}

	/** 매핑에 없는 잡의 표시명 — 접두어를 뗀 잡 코드명 그대로. */
	private static String fallbackLabel(String key) {
		return key.startsWith(CRAWLER_PREFIX) ? key.substring(CRAWLER_PREFIX.length()) : key;
	}

	private static LocalDate later(LocalDate current, LocalDate candidate) {
		return current == null || candidate.isAfter(current) ? candidate : current;
	}

	/** 반올림하지 않는다 — 서버가 반올림하면 구간 합과 총합이 어긋난다(설계 §2). */
	private static BigDecimal cost(BigDecimal unitPrice, long calls) {
		return unitPrice.multiply(BigDecimal.valueOf(calls));
	}

	/** 소스 1종의 읽기 결과 — available=false면 집계는 0이고 "0을 썼다"가 아니라 "모름"이라는 뜻이다. */
	private record SourceRead(boolean available, LocalDate latestCallOn) {
	}
}
