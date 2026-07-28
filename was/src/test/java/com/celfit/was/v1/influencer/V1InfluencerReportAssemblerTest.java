package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.v1.influencer.V1InfluencerReportRepository.BrandRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CategoryRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CopyRow;
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

	// 기준시각 고정 — 경과일·isActive 판정이 결정적이도록.
	private final Clock fixedClock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
	private final OffsetDateTime now = OffsetDateTime.parse("2026-07-15T00:00:00Z");
	private final V1InfluencerReportAssembler assembler =
			new V1InfluencerReportAssembler(fixedClock, new ObjectMapper());

	/** 전 필드 채운 summary — 광고 3일 전, 마지막 업로드 5일 전. */
	private SummaryRow fullSummary() {
		return new SummaryRow(24L, 321L, "views", 52000L, new BigDecimal("0.42"),
				new BigDecimal("3.10"), 1500L, 80L, "up", 6L,
				60000L, 42000L, 30, 18L, 6L,
				OffsetDateTime.parse("2026-07-12T00:00:00Z"),
				OffsetDateTime.parse("2026-07-10T00:00:00Z"),
				new BigDecimal("2.5"));
	}

	private SummaryRow summaryWith(Long organicAvg, Long adAvg, OffsetDateTime lastPostedAt) {
		return new SummaryRow(24L, 321L, "views", 52000L, new BigDecimal("0.42"),
				new BigDecimal("3.10"), 1500L, 80L, "up", 6L,
				organicAvg, adAvg, 30, 18L, 6L,
				null, lastPostedAt, new BigDecimal("2.5"));
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
	void strip은_bars와_같은_올린_순의_sponsored_추출() {
		var series = List.of(
				new SeriesRow(OffsetDateTime.parse("2026-06-30T20:30:00Z"), "reels", 1000L, 100L, 10L, false),
				new SeriesRow(OffsetDateTime.parse("2026-07-05T03:00:00Z"), "feed", null, 200L, 20L, true));

		InfluencerAiReport report = assembler.toReport(fullSummary(), null, series, List.of(), List.of());

		// bars 순서(올린 순) 그대로, postedAt은 KST 달력 날짜
		assertThat(report.chart().bars()).containsExactly(
				new InfluencerAiReport.Chart.Bar(1000L, 100L, 10L, "2026-07-01", false, "reels"),
				new InfluencerAiReport.Chart.Bar(null, 200L, 20L, "2026-07-05", true, "feed"));
		assertThat(report.ads().strip()).containsExactly(false, true);
	}

	@Test
	void 광고블록_sponsoredCount_comparison_lastAdNote는_series의_adType에서_계산() {
		// summary는 옛 ad_marked 집계(6·60000·42000·광고 07-12)를 담지만, 이제 무시하고 series로 계산한다.
		var series = List.of(
				new SeriesRow(OffsetDateTime.parse("2026-07-01T00:00:00Z"), "reels", 10000L, 100L, 10L, false),
				new SeriesRow(OffsetDateTime.parse("2026-07-03T00:00:00Z"), "reels", 20000L, 200L, 20L, false),
				new SeriesRow(OffsetDateTime.parse("2026-07-05T00:00:00Z"), "reels", 6000L, 300L, 30L, true),
				new SeriesRow(OffsetDateTime.parse("2026-07-08T00:00:00Z"), "reels", 4000L, 400L, 40L, true));

		var ads = assembler.toReport(fullSummary(), null, series, List.of(), List.of()).ads();

		assertThat(ads.sponsoredCount()).isEqualTo(2L); // summary의 6이 아니라 series의 sponsored 수
		assertThat(ads.strip()).containsExactly(false, false, true, true);
		// metric=views: organic avg(10000,20000)=15000, ad avg(6000,4000)=5000, drop=round((1-5000/15000)*100)=67
		assertThat(ads.comparison()).isEqualTo(
				new InfluencerAiReport.Ads.Comparison("views", 2L, 15000L, 2L, 5000L, 67));
		// 마지막 광고 = series 마지막 sponsored(07-08), now=07-15 → 7일 → "마지막 광고 1주 전"(summary 07-12 아님)
		assertThat(ads.lastAdNote()).isEqualTo("마지막 광고 1주 전");
	}

	@Test
	void comparison은_organic과_ad_표본이_둘_다_있어야_존재() {
		// metric=views. 광고 표본(views>0)이 없으면 null.
		var organicOnly = List.of(
				new SeriesRow(OffsetDateTime.parse("2026-07-01T00:00:00Z"), "reels", 10000L, 100L, 10L, false));
		assertThat(assembler.toReport(fullSummary(), null, organicOnly, List.of(), List.of())
				.ads().comparison()).isNull();
		// 유기 표본이 없어도 null.
		var adOnly = List.of(
				new SeriesRow(OffsetDateTime.parse("2026-07-01T00:00:00Z"), "reels", 5000L, 100L, 10L, true));
		assertThat(assembler.toReport(fullSummary(), null, adOnly, List.of(), List.of())
				.ads().comparison()).isNull();
	}

	@Test
	void 카피_없음이면_카피_필드만_null_블록_구조는_유지() {
		InfluencerAiReport report = assembler.toReport(fullSummary(), null, List.of(), List.of(), List.of());

		assertThat(report.tagline()).isNull();
		assertThat(report.summary()).isNull();
		assertThat(report.trend().note()).isNull();
		assertThat(report.chart().note()).isNull();
		assertThat(report.contentMix().traits()).isEmpty();
		assertThat(report.ads().headline()).isNull();
		assertThat(report.activity().paceNote()).isNull();
		// 카피와 무관한 값은 그대로
		assertThat(report.trend().direction()).isEqualTo("up");
		assertThat(report.chart().metric()).isEqualTo("views");
		assertThat(report.stats().metric()).isEqualTo("views");
	}

	@Test
	void activity_경과일은_24시간_단위_isActive는_14일_경계_포함() {
		// 5일 전 업로드
		var active = assembler.toReport(fullSummary(), null, List.of(), List.of(), List.of()).activity();
		assertThat(active.lastUploadDaysAgo()).isEqualTo(5L);
		assertThat(active.isActive()).isTrue();

		// 정확히 14일 전 → true (스펙 6.5: <= 14)
		var boundary = assembler.toReport(summaryWith(null, null, now.minusDays(14)),
				null, List.of(), List.of(), List.of()).activity();
		assertThat(boundary.lastUploadDaysAgo()).isEqualTo(14L);
		assertThat(boundary.isActive()).isTrue();

		// 15일 전 → false
		var inactive = assembler.toReport(summaryWith(null, null, now.minusDays(15)),
				null, List.of(), List.of(), List.of()).activity();
		assertThat(inactive.lastUploadDaysAgo()).isEqualTo(15L);
		assertThat(inactive.isActive()).isFalse();

		// 업로드 이력 없음 → null·false
		var unknown = assembler.toReport(summaryWith(null, null, null),
				null, List.of(), List.of(), List.of()).activity();
		assertThat(unknown.lastUploadDaysAgo()).isNull();
		assertThat(unknown.isActive()).isFalse();
	}

	@Test
	void toReport는_스펙_6_5_구조로_조립한다() {
		var copy = new CopyRow("태그라인", "요약", "추세 노트", "차트 노트",
				"[\"뷰티\",\"유머\"]", "광고 헤드라인", "페이스 노트");
		var categories = List.of(new CategoryRow("메이크업", 5L), new CategoryRow("스킨케어", 2L));
		var brands = List.of(new BrandRow("머지", 3L));

		InfluencerAiReport report = assembler.toReport(fullSummary(), copy, List.of(), categories, brands);

		assertThat(report.tagline()).isEqualTo("태그라인");
		assertThat(report.analyzedCount()).isEqualTo(24L);
		assertThat(report.totalPosts()).isEqualTo(321L);
		assertThat(report.summary()).isEqualTo("요약");
		assertThat(report.stats()).isEqualTo(new InfluencerAiReport.Stats(
				"views", 52000L, new BigDecimal("0.42"), new BigDecimal("3.10"), 1500L, 80L));
		assertThat(report.trend()).isEqualTo(new InfluencerAiReport.Trend("up", "추세 노트"));
		assertThat(report.chart().metric()).isEqualTo("views");
		assertThat(report.chart().note()).isEqualTo("차트 노트");
		assertThat(report.contentMix().categories()).containsExactly(
				new InfluencerAiReport.ContentMix.Category("메이크업", 5L),
				new InfluencerAiReport.ContentMix.Category("스킨케어", 2L));
		assertThat(report.contentMix().traits()).containsExactly("뷰티", "유머");
		// series 없음 → 광고 집계는 series에서 나오므로 0·null (광고 계산은 전용 테스트가 검증)
		assertThat(report.ads().sponsoredCount()).isEqualTo(0L);
		assertThat(report.ads().lastAdNote()).isNull();
		assertThat(report.ads().headline()).isEqualTo("광고 헤드라인");
		assertThat(report.ads().brands()).containsExactly(new InfluencerAiReport.Ads.Brand("머지", 3L));
		assertThat(report.activity().avgIntervalDays()).isEqualByComparingTo("2.5");
		assertThat(report.activity().paceNote()).isEqualTo("페이스 노트");
	}
}
