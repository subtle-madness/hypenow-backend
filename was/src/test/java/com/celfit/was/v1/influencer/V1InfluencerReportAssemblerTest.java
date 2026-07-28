package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.v1.influencer.V1InfluencerReportRepository.BrandRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CategoryRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CopyRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.PeerStatsRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.ProductRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.SeriesRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.SummaryRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class V1InfluencerReportAssemblerTest {

	// 기준시각 고정 — 경과일·isActive·헤드라인 판정이 결정적이도록.
	private final Clock fixedClock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
	private final OffsetDateTime now = OffsetDateTime.parse("2026-07-15T00:00:00Z");
	private final V1InfluencerReportAssembler assembler =
			new V1InfluencerReportAssembler(fixedClock, new ObjectMapper());

	/** 전 필드 채운 summary — 팔로워 10000, 마지막 업로드 5일 전(07-10). */
	private SummaryRow fullSummary() {
		return new SummaryRow(10000L, 12L, 187L, "views", 52000L, new BigDecimal("0.42"),
				new BigDecimal("3.10"), 1500L, 80L,
				OffsetDateTime.parse("2026-07-10T00:00:00Z"), new BigDecimal("2.5"));
	}

	private SummaryRow summaryWith(OffsetDateTime lastPostedAt) {
		return new SummaryRow(10000L, 12L, 187L, "views", 52000L, new BigDecimal("0.42"),
				new BigDecimal("3.10"), 1500L, 80L, lastPostedAt, new BigDecimal("2.5"));
	}

	/** 피어 표본 20건 — topPct 8종 검증용 (유효 팔로워는 07-28부터 피어 무관). */
	private PeerStatsRow peer() {
		return new PeerStatsRow(20L, 18, 26, 32, 45, 39, 42, 48, 53,
				new BigDecimal("2.0"), new BigDecimal("2.4"));
	}

	private SeriesRow row(String at, Long views, long likes, long comments, boolean sp) {
		return new SeriesRow(OffsetDateTime.parse(at), "reels", views, likes, comments, sp,
				"캡션", "/img/t.jpg", sp ? "브랜드A" : null);
	}

	@Test
	void lastAdNote는_7일_미만_이번주_그_외_N주_전() {
		assertThat(assembler.lastAdNote(null, now)).isNull();
		assertThat(assembler.lastAdNote(now.minusDays(3), now)).isEqualTo("이번 주 광고");
		assertThat(assembler.lastAdNote(now.minusDays(7), now)).isEqualTo("마지막 광고 1주 전");
		assertThat(assembler.lastAdNote(now.minusDays(8), now)).isEqualTo("마지막 광고 1주 전");
		assertThat(assembler.lastAdNote(now.minusDays(42), now)).isEqualTo("마지막 광고 6주 전");
	}

	@Test
	void strip은_bars와_같은_올린_순의_sponsored_추출이고_캡션_썸네일_브랜드는_그대로_통과한다() {
		var series = List.of(
				row("2026-06-30T20:30:00Z", 1000L, 100, 10, false),
				row("2026-07-05T03:00:00Z", null, 200, 20, true));

		InfluencerAiReport report = assembler.toReport(fullSummary(), null, series, List.of(), List.of(),
				List.of(), peer());

		// bars 순서(올린 순) 그대로, postedAt은 KST 달력 날짜, caption·thumbnailUrl·brand는 그대로 통과
		assertThat(report.chart().bars()).containsExactly(
				new InfluencerAiReport.Chart.Bar(1000L, 100L, 10L, "2026-07-01", false, "reels",
						"캡션", "/img/t.jpg", null),
				new InfluencerAiReport.Chart.Bar(null, 200L, 20L, "2026-07-05", true, "reels",
						"캡션", "/img/t.jpg", "브랜드A"));
		assertThat(report.ads().strip()).containsExactly(false, true);
	}

	@Test
	void 카피_없음이면_카피_필드만_null_블록_구조는_유지() {
		InfluencerAiReport report = assembler.toReport(fullSummary(), null, List.of(), List.of(), List.of(),
				List.of(), peer());

		assertThat(report.tagline()).isNull();
		assertThat(report.stats().perfSummary()).isNull();
		assertThat(report.contentMix().contentSummary()).isNull();
		assertThat(report.ads().adSummary()).isNull();
		assertThat(report.contentMix().traits()).isEmpty();
		assertThat(report.ads().headline()).isNull(); // 광고 이력 없음
		// 카피와 무관한 값은 그대로
		assertThat(report.chart().metric()).isEqualTo("views");
		assertThat(report.stats().metric()).isEqualTo("views");
	}

	@Test
	void activity_경과일은_24시간_단위_isActive는_14일_경계_포함() {
		// 5일 전 업로드
		var active = assembler.toReport(fullSummary(), null, List.of(), List.of(), List.of(),
				List.of(), peer()).activity();
		assertThat(active.lastUploadDaysAgo()).isEqualTo(5L);
		assertThat(active.isActive()).isTrue();

		// 정확히 14일 전 → true (스펙 6.5: <= 14)
		var boundary = assembler.toReport(summaryWith(now.minusDays(14)), null, List.of(), List.of(),
				List.of(), List.of(), peer()).activity();
		assertThat(boundary.lastUploadDaysAgo()).isEqualTo(14L);
		assertThat(boundary.isActive()).isTrue();

		// 15일 전 → false
		var inactive = assembler.toReport(summaryWith(now.minusDays(15)), null, List.of(), List.of(),
				List.of(), List.of(), peer()).activity();
		assertThat(inactive.lastUploadDaysAgo()).isEqualTo(15L);
		assertThat(inactive.isActive()).isFalse();

		// 업로드 이력 없음 → null·false
		var unknown = assembler.toReport(summaryWith(null), null, List.of(), List.of(), List.of(),
				List.of(), peer()).activity();
		assertThat(unknown.lastUploadDaysAgo()).isNull();
		assertThat(unknown.isActive()).isFalse();
	}

	@Test
	void toReport는_스펙_6_5_구조로_조립한다() {
		var copy = new CopyRow("태그라인", "[\"뷰티\",\"유머\"]", "성과 요약", "콘텐츠 요약", "광고 요약");
		var categories = List.of(new CategoryRow("메이크업", 5L), new CategoryRow("스킨케어", 2L));
		var brands = List.of(new BrandRow("머지", 3L));
		var products = List.of(new ProductRow("립스틱", 2L));

		InfluencerAiReport report = assembler.toReport(fullSummary(), copy, List.of(), categories, brands,
				products, peer());

		assertThat(report.tagline()).isEqualTo("태그라인");
		assertThat(report.analyzedCount()).isEqualTo(12L);
		assertThat(report.totalPosts()).isEqualTo(187L);
		assertThat(report.stats().metric()).isEqualTo("views");
		assertThat(report.stats().perfSummary()).isEqualTo("성과 요약");
		assertThat(report.chart().metric()).isEqualTo("views");
		assertThat(report.contentMix().contentSummary()).isEqualTo("콘텐츠 요약");
		assertThat(report.contentMix().categories()).containsExactly(
				new InfluencerAiReport.ContentMix.Category("메이크업", 5L),
				new InfluencerAiReport.ContentMix.Category("스킨케어", 2L));
		assertThat(report.contentMix().traits()).containsExactly("뷰티", "유머");
		assertThat(report.ads().adSummary()).isEqualTo("광고 요약");
		assertThat(report.ads().brands()).containsExactly(new InfluencerAiReport.Ads.Brand("머지", 3L));
		assertThat(report.ads().products()).containsExactly(new InfluencerAiReport.Ads.Product("립스틱", 2L));
		// series 없음 → 광고 집계는 series에서 나오므로 0·null (광고 계산은 전용 테스트가 검증)
		assertThat(report.ads().sponsoredCount()).isEqualTo(0L);
		assertThat(report.ads().lastAdNote()).isNull();
		assertThat(report.activity().avgIntervalDays()).isEqualByComparingTo("2.5");
	}

	@Test
	void 성장세는_앞절반_뒤절반_평균_증감률() {
		var series = List.of(
				row("2026-07-01T00:00:00Z", 8000L, 100, 10, false),
				row("2026-07-02T00:00:00Z", 12000L, 100, 10, false),
				row("2026-07-03T00:00:00Z", 14000L, 100, 10, false),
				row("2026-07-04T00:00:00Z", 16000L, 100, 10, false));
		var report = assembler.toReport(fullSummary(), null, series, List.of(), List.of(),
				List.of(), peer());
		assertThat(report.stats().overall().views().growthPct()).isEqualTo(50);
	}

	@Test
	void 광고행은_sponsored만으로_계산하고_광고_없으면_null() {
		var noAds = List.of(row("2026-07-01T00:00:00Z", 8000L, 100, 10, false));
		assertThat(assembler.toReport(fullSummary(), null, noAds, List.of(), List.of(),
				List.of(), peer()).stats().ad()).isNull();

		var withAds = List.of(
				row("2026-07-01T00:00:00Z", 10000L, 100, 10, false),
				row("2026-07-02T00:00:00Z", 6000L, 300, 30, true),
				row("2026-07-03T00:00:00Z", 4000L, 400, 40, true));
		var ad = assembler.toReport(fullSummary(), null, withAds, List.of(), List.of(),
				List.of(), peer()).stats().ad();
		assertThat(ad.views().value()).isEqualByComparingTo("5000");
		assertThat(ad.views().topPct()).isEqualTo(39);
	}

	@Test
	void 유효_팔로워는_게시물당_평균_실반응() {
		// views(1000) < followers(10000) → 안분 없음. e = 100+10 = 110씩 2건 → 평균 110, pct 1(=1.1 반올림)
		var series = List.of(
				row("2026-07-01T00:00:00Z", 1000L, 100, 10, false),
				row("2026-07-02T00:00:00Z", 1000L, 100, 10, false));
		var r = assembler.toReport(fullSummary(), null, series, List.of(), List.of(),
				List.of(), peer());
		assertThat(r.effectiveFollowers()).isEqualTo(110L);
		assertThat(r.effectiveFollowersPct()).isEqualTo(1);
	}

	@Test
	void 유효_팔로워_바이럴은_팔로워_비중으로_안분() {
		// views 100000 = 팔로워의 10배 → 반응 5100 중 팔로워 몫 1/10 = 510
		var series = List.of(row("2026-07-01T00:00:00Z", 100000L, 5000, 100, false));
		var r = assembler.toReport(fullSummary(), null, series, List.of(), List.of(),
				List.of(), peer());
		assertThat(r.effectiveFollowers()).isEqualTo(510L);
		assertThat(r.effectiveFollowersPct()).isEqualTo(5);
	}

	@Test
	void 유효_팔로워_비정상_좋아요는_댓글_앵커로_컷() {
		// 피드(views null) 좋아요 5000·댓글 10 — 좋아요:댓글 500:1은 정상 범위(39:1) 밖 → 39×(10+1)=429로 컷
		var series = List.of(row("2026-07-01T00:00:00Z", null, 5000, 10, false));
		var r = assembler.toReport(fullSummary(), null, series, List.of(), List.of(),
				List.of(), peer());
		assertThat(r.effectiveFollowers()).isEqualTo(429L);
		assertThat(r.effectiveFollowersPct()).isEqualTo(4);
	}

	@Test
	void 시계열_없으면_유효팔로워_null_피어_없으면_topPct만_null() {
		// 유효 팔로워는 시계열 기반 측정값 — 시계열이 없으면 null, 피어 유무와는 무관
		var empty = assembler.toReport(fullSummary(), null, List.of(), List.of(), List.of(),
				List.of(), peer());
		assertThat(empty.effectiveFollowers()).isNull();

		var noPeer = assembler.toReport(fullSummary(), null,
				List.of(row("2026-07-01T00:00:00Z", 1000L, 100, 10, false)),
				List.of(), List.of(), List.of(), null);
		assertThat(noPeer.stats().overall().views().topPct()).isNull();
		assertThat(noPeer.effectiveFollowers()).isEqualTo(110L);
	}

	@Test
	void 헤드라인은_사실값_템플릿() {
		// now 고정 2026-07-15, 마지막 광고 07-05(10일 전), 광고 2건 간격 4일, 최다 브랜드 "브랜드A"
		var series = List.of(
				row("2026-07-01T00:00:00Z", 10000L, 100, 10, true),
				row("2026-07-05T00:00:00Z", 8000L, 100, 10, true));
		var ads = assembler.toReport(fullSummary(), null, series, List.of(),
				List.of(new BrandRow("브랜드A", 2L)), List.of(), peer()).ads();
		assertThat(ads.headline()).isEqualTo("최근 10일 전 브랜드A 협업 · 평균 4일 간격으로 광고 진행");
		assertThat(ads.adIntervalDays()).isEqualByComparingTo("4.0");
		assertThat(ads.lastAdDaysAgo()).isEqualTo(10L);
	}

	@Test
	void 피어_3계정_미만이면_topPct만_숨김() {
		// topPct(피어 비교)는 최소표본 게이트 대상. 유효 팔로워는 시계열 측정값이라 피어와 무관 (07-28 분리)
		var tiny = new PeerStatsRow(2L, 18, 26, 32, 45, 39, 42, 48, 53,
				new BigDecimal("2.0"), new BigDecimal("2.4"));
		var r = assembler.toReport(fullSummary(), null,
				List.of(row("2026-07-01T00:00:00Z", 1000L, 100, 10, false)),
				List.of(), List.of(), List.of(), tiny);
		assertThat(r.stats().overall().views().topPct()).isNull();
		assertThat(r.effectiveFollowers()).isEqualTo(110L);
	}

	@Test
	void 성장세는_값이_전부_0이거나_null이면_null() {
		var series = List.of(
				row("2026-07-01T00:00:00Z", 0L, 100, 10, false),
				row("2026-07-02T00:00:00Z", null, 100, 10, false));
		var report = assembler.toReport(fullSummary(), null, series, List.of(), List.of(),
				List.of(), peer());
		assertThat(report.stats().overall().views().growthPct()).isNull();
	}

	@Test
	void 광고_간격은_전부_같은_날이면_스팬_0이라_null() {
		var series = List.of(
				row("2026-07-05T00:00:00Z", 10000L, 100, 10, true),
				row("2026-07-05T00:00:00Z", 8000L, 100, 10, true));
		var ads = assembler.toReport(fullSummary(), null, series, List.of(), List.of(),
				List.of(), peer()).ads();
		assertThat(ads.adIntervalDays()).isNull();
	}
}
