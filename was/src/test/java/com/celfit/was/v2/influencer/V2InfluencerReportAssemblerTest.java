package com.celfit.was.v2.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.v2.influencer.V2InfluencerReportRepository.BrandCollabRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.CategoryRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.CopyRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.SeriesRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.SummaryRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 스펙 6.22 조립 계약 — 산식 정의는 각 메서드 javadoc. */
class V2InfluencerReportAssemblerTest {

	// 기준 시각 고정: 2026-07-28T00:00Z
	private final Clock clock = Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC);
	private final V2InfluencerReportAssembler assembler =
			new V2InfluencerReportAssembler(clock, new ObjectMapper());

	private SummaryRow summary() {
		return new SummaryRow(10_000L, 12L, 214L, 52_000L, new BigDecimal("5.2"),
				new BigDecimal("3.14"), 1_500L, 80L,
				OffsetDateTime.parse("2026-07-23T00:00:00Z"), new BigDecimal("6.2"));
	}

	private CopyRow copy() {
		return new CopyRow("태그라인", "[\"정보형\",\"스킨케어\"]", "성과 요약", "콘텐츠 요약", "광고 요약");
	}

	private SeriesRow row(String at, Long views, long likes, long comments, boolean sp) {
		return new SeriesRow(OffsetDateTime.parse(at), views == null ? "feed" : "reels",
				views, likes, comments, sp);
	}

	@Test
	void 요약_3종은_톱레벨이고_광고_없으면_adsSummary_null() {
		var noAds = List.of(row("2026-07-01T00:00:00Z", 1000L, 100, 10, false));
		var r = assembler.toReport(summary(), copy(), noAds, List.of(), List.of());
		assertThat(r.perfSummary()).isEqualTo("성과 요약");
		assertThat(r.contentSummary()).isEqualTo("콘텐츠 요약");
		assertThat(r.adsSummary()).isNull();     // 광고 0건 — 저장된 문구가 있어도 서빙 안 함(스펙 6.22)
		assertThat(r.sponsored()).isNull();
		assertThat(r.ads().sponsoredCount()).isZero(); // ads 블록 자체는 항상 존재

		var withAds = List.of(row("2026-07-01T00:00:00Z", 1000L, 100, 10, true));
		assertThat(assembler.toReport(summary(), copy(), withAds, List.of(), List.of())
				.adsSummary()).isEqualTo("광고 요약");
	}

	@Test
	void overall_세트는_summary_값과_series_성장세() {
		// views 앞절반(2건) 평균 10000, 뒤절반 평균 15000 → +50%
		var series = List.of(
				row("2026-07-01T00:00:00Z", 8_000L, 100, 10, false),
				row("2026-07-02T00:00:00Z", 12_000L, 100, 10, false),
				row("2026-07-03T00:00:00Z", 14_000L, 100, 10, false),
				row("2026-07-04T00:00:00Z", 16_000L, 100, 10, false));
		var overall = assembler.toReport(summary(), copy(), series, List.of(), List.of()).overall();
		assertThat(overall.views().value()).isEqualByComparingTo("52000");
		assertThat(overall.views().growthPct()).isEqualTo(50);
		assertThat(overall.er().value()).isEqualByComparingTo("3.1"); // 소수 1자리(HALF_UP)
		assertThat(overall.viewsPerFollower()).isEqualByComparingTo("5.2");
		assertThat(overall.sampleCount()).isEqualTo(12L); // analyzedCount
	}

	@Test
	void sponsored_세트는_광고만_재계산() {
		var series = List.of(
				row("2026-07-01T00:00:00Z", 10_000L, 100, 10, false),
				row("2026-07-02T00:00:00Z", 6_000L, 300, 30, true),
				row("2026-07-03T00:00:00Z", 4_000L, 400, 40, true));
		var sp = assembler.toReport(summary(), copy(), series, List.of(), List.of()).sponsored();
		assertThat(sp.views().value()).isEqualByComparingTo("5000");      // (6000+4000)/2
		assertThat(sp.er().value()).isEqualByComparingTo("3.9");          // avg(330,440)×100/10000 = 3.85 → 3.9
		assertThat(sp.viewsPerFollower()).isEqualByComparingTo("0.5");    // 5000/10000
		assertThat(sp.sampleCount()).isEqualTo(2L);
		assertThat(sp.views().growthPct()).isEqualTo(-33);                // 앞 6000 vs 뒤 4000 → -33%
	}

	@Test
	void viewsTrend는_조회수_공개만_올린_순_최대_8_그리고_2개_미만이면_빈배열() {
		// 피드(views null) 제외, 릴스 10건 → 최신 8건만, 올린 순 유지
		var series = new java.util.ArrayList<SeriesRow>();
		series.add(row("2026-06-30T00:00:00Z", null, 50, 5, false)); // 피드 — 제외
		for (int i = 1; i <= 10; i++) {
			series.add(row("2026-07-%02dT00:00:00Z".formatted(i), 1_000L * i, 100, 10, false));
		}
		var trend = assembler.toReport(summary(), copy(), series, List.of(), List.of()).viewsTrend();
		assertThat(trend).hasSize(8);
		assertThat(trend.get(0).date()).isEqualTo("2026-07-03"); // 앞 2건 잘림(00Z=09KST라 날짜 동일)
		assertThat(trend.get(7).value()).isEqualByComparingTo("10000");

		var one = List.of(row("2026-07-01T00:00:00Z", 1_000L, 100, 10, false));
		assertThat(assembler.toReport(summary(), copy(), one, List.of(), List.of()).viewsTrend())
				.isEmpty(); // 1건 → 추이 불가
	}

	@Test
	void erTrend는_전체_게시물_게시물당_참여율() {
		// (100+10)×100/10000 = 1.1
		var series = List.of(row("2026-07-01T00:00:00Z", null, 100, 10, false));
		var trend = assembler.toReport(summary(), copy(), series, List.of(), List.of()).erTrend();
		assertThat(trend).hasSize(1);
		assertThat(trend.get(0).value()).isEqualByComparingTo("1.1");
	}

	@Test
	void ads는_정수_간격과_사실값_헤드라인_브랜드_중첩() {
		// 광고 3건: 07-01·07-05·07-09 → 스팬 8일/2 = 4일, 마지막 광고 19일 전(기준 07-28)
		var series = List.of(
				row("2026-07-01T00:00:00Z", 1_000L, 100, 10, true),
				row("2026-07-05T00:00:00Z", 1_000L, 100, 10, true),
				row("2026-07-09T00:00:00Z", 1_000L, 100, 10, true));
		var collabs = List.of(new BrandCollabRow("브랜드A", 2L,
				"[\"c1\", \"c2\"]", "[\"other1\", \"other2\"]"));
		var ads = assembler.toReport(summary(), copy(), series, List.of(), collabs).ads();
		assertThat(ads.sponsoredCount()).isEqualTo(3L);
		assertThat(ads.adIntervalDays()).isEqualTo(4L);   // 정수(스펙: 소수 없음)
		assertThat(ads.lastAdDaysAgo()).isEqualTo(19L);
		assertThat(ads.headline()).isEqualTo("최근 19일 전 브랜드A 협업 · 평균 4일 간격으로 광고 진행");
		assertThat(ads.brands().get(0).otherInfluencers().get(0).id()).isEqualTo("other1");
		assertThat(ads.brands().get(0).otherInfluencers().get(0).handle()).isEqualTo("other1");
		assertThat(ads.brands().get(0).contentIds()).containsExactly("c1", "c2");
	}

	@Test
	void 유효_팔로워와_activity() {
		var series = List.of(
				row("2026-07-01T00:00:00Z", null, 100L, 10L, false),
				row("2026-07-02T00:00:00Z", null, 200L, 20L, false));
		var r = assembler.toReport(summary(), copy(), series, List.of(), List.of());
		assertThat(r.effectiveFollowers()).isEqualTo(165L); // EffectiveFollowersTest 기본 케이스와 동일
		assertThat(r.activity().lastUploadDaysAgo()).isEqualTo(5L);
		assertThat(r.activity().avgIntervalDays()).isEqualByComparingTo("6.2");
	}

	@Test
	void contentMix와_traits() {
		var r = assembler.toReport(summary(), copy(), List.of(),
				List.of(new CategoryRow("메이크업", 7L)), List.of());
		assertThat(r.contentMix().categories().get(0).label()).isEqualTo("메이크업");
		assertThat(r.contentMix().traits()).containsExactly("정보형", "스킨케어");
		assertThat(r.tagline()).isEqualTo("태그라인");
		assertThat(r.analyzedCount()).isEqualTo(12L);
		assertThat(r.totalPosts()).isEqualTo(214L);
	}
}
