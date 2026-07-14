package com.celfit.was.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.AccountCategoryStat;
import com.celfit.contract.analysis.AccountContentPoint;
import com.celfit.contract.analysis.AccountSummary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class InfluencerDetailAssemblerTest {

	// 시드 기준일(계획서) — 2026-07-14T00:00Z
	private final Clock fixedClock = Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC);

	private final InfluencerDetailAssembler assembler = new InfluencerDetailAssembler(fixedClock);

	private AccountSummary glowSummary() {
		return new AccountSummary("glow", 42555L, 312L, 486L, "건성 8년차",
				12L, 10L, "views", 30600L, new BigDecimal("1.3"),
				new BigDecimal("4.3"), 5515L, 90L,
				"up", 18, 25000L, 29500L,
				2L, 30600L, 26900L, 12,
				6L, 2L, OffsetDateTime.parse("2026-07-11T00:00:00Z"),
				OffsetDateTime.parse("2026-07-12T00:00:00Z"), new BigDecimal("2.4"));
	}

	private Account glowAccount() {
		return new Account("glow", "글로우", "https://pic/glow.jpg", 42555L);
	}

	private List<AccountCategoryStat> glowCategoryStats() {
		return List.of(
				new AccountCategoryStat("glow", "스킨케어", 7L),
				new AccountCategoryStat("glow", "메이크업", 3L),
				new AccountCategoryStat("glow", "클렌징", 3L));
	}

	private List<AccountContentPoint> glowSeries() {
		return List.of(
				new AccountContentPoint("g1", "glow", OffsetDateTime.parse("2026-07-01T00:00:00Z"),
						"reels", 1000L, 100L, 10L, false),
				new AccountContentPoint("g2", "glow", OffsetDateTime.parse("2026-07-05T00:00:00Z"),
						"feed", null, 300L, 30L, true),
				new AccountContentPoint("g3", "glow", OffsetDateTime.parse("2026-07-08T00:00:00Z"),
						"reels", 2000L, 150L, 15L, false));
	}

	@Test
	void 전체_블록을_조립한다() {
		InfluencerDetailResponse response =
				assembler.toResponse(glowSummary(), glowAccount(), glowCategoryStats(), glowSeries());

		assertThat(response.profile().handle()).isEqualTo("glow");
		assertThat(response.profile().displayName()).isEqualTo("글로우");
		assertThat(response.profile().profileImageUrl()).isEqualTo("https://pic/glow.jpg");
		assertThat(response.profile().followers()).isEqualTo(42555L);
		assertThat(response.profile().followsCount()).isEqualTo(312L);
		assertThat(response.profile().postsCount()).isEqualTo(486L);
		assertThat(response.profile().biography()).isEqualTo("건성 8년차");

		InfluencerDetailResponse.Report report = response.report();
		assertThat(report.totalPosts()).isEqualTo(486L);
		assertThat(report.analyzedCount()).isEqualTo(12L);

		assertThat(report.stats().metric()).isEqualTo("views");
		assertThat(report.stats().avgViews()).isEqualTo(30600L);
		assertThat(report.stats().viewsPerFollower()).isEqualByComparingTo(new BigDecimal("1.3"));
		assertThat(report.stats().avgErPct()).isEqualByComparingTo(new BigDecimal("4.3"));
		assertThat(report.stats().avgLikes()).isEqualTo(5515L);
		assertThat(report.stats().avgComments()).isEqualTo(90L);

		assertThat(report.trend().direction()).isEqualTo("up");
		assertThat(report.trend().changePct()).isEqualTo(18);
		assertThat(report.trend().olderAvg()).isEqualTo(25000L);
		assertThat(report.trend().newerAvg()).isEqualTo(29500L);

		assertThat(report.chart().metric()).isEqualTo("views");
		assertThat(report.chart().bars()).extracting(InfluencerDetailResponse.Report.Bar::shortCode)
				.containsExactly("g1", "g2", "g3");
		assertThat(report.chart().bars().get(1).views()).isNull();
		assertThat(report.chart().bars().get(1).sponsored()).isTrue();

		assertThat(report.contentMix().categories())
				.extracting(InfluencerDetailResponse.Report.Category::label)
				.containsExactly("스킨케어", "메이크업", "클렌징");
		assertThat(report.contentMix().categories().getFirst().count()).isEqualTo(7L);

		assertThat(report.ads().sponsoredCount()).isEqualTo(2L);
		assertThat(report.ads().strip()).containsExactly(false, true, false);
		assertThat(report.ads().lastAdPostedAt()).isEqualTo(OffsetDateTime.parse("2026-07-11T00:00:00Z"));
		assertThat(report.ads().lastAdNote()).isEqualTo("마지막 광고 3일 전");
		assertThat(report.ads().comparison()).isNotNull();
		assertThat(report.ads().comparison().metric()).isEqualTo("views");
		assertThat(report.ads().comparison().organicCount()).isEqualTo(6L);
		assertThat(report.ads().comparison().adCount()).isEqualTo(2L);
		assertThat(report.ads().comparison().organicAvg()).isEqualTo(30600L);
		assertThat(report.ads().comparison().adAvg()).isEqualTo(26900L);
		assertThat(report.ads().comparison().adDropPct()).isEqualTo(12);

		assertThat(report.activity().lastPostedAt()).isEqualTo(OffsetDateTime.parse("2026-07-12T00:00:00Z"));
		assertThat(report.activity().lastUploadDaysAgo()).isEqualTo(2L);
		assertThat(report.activity().isActive()).isTrue();
		assertThat(report.activity().avgIntervalDays()).isEqualByComparingTo(new BigDecimal("2.4"));
	}

	@Test
	void accounts가_없으면_표시_필드만_null이고_summaries_값은_유지된다() {
		InfluencerDetailResponse response =
				assembler.toResponse(glowSummary(), null, glowCategoryStats(), glowSeries());

		assertThat(response.profile().displayName()).isNull();
		assertThat(response.profile().profileImageUrl()).isNull();
		assertThat(response.profile().handle()).isEqualTo("glow");
		assertThat(response.profile().followers()).isEqualTo(42555L);
		assertThat(response.profile().followsCount()).isEqualTo(312L);
		assertThat(response.profile().postsCount()).isEqualTo(486L);
		assertThat(response.profile().biography()).isEqualTo("건성 8년차");
	}

	@Test
	void 마지막_광고가_없으면_광고_노트와_비교_블록이_null이다() {
		AccountSummary noAd = new AccountSummary("glow", 42555L, 312L, 486L, "건성 8년차",
				12L, 10L, "views", 30600L, new BigDecimal("1.3"),
				new BigDecimal("4.3"), 5515L, 90L,
				"up", 18, 25000L, 29500L,
				0L, null, null, null,
				null, null, null,
				OffsetDateTime.parse("2026-07-12T00:00:00Z"), new BigDecimal("2.4"));

		InfluencerDetailResponse response =
				assembler.toResponse(noAd, glowAccount(), glowCategoryStats(), glowSeries());

		assertThat(response.report().ads().lastAdPostedAt()).isNull();
		assertThat(response.report().ads().lastAdNote()).isNull();
		assertThat(response.report().ads().comparison()).isNull();
	}

	@Test
	void 오늘_업로드면_경과일_0이고_오늘_문구다() {
		AccountSummary today = new AccountSummary("glow", 42555L, 312L, 486L, "건성 8년차",
				12L, 10L, "views", 30600L, new BigDecimal("1.3"),
				new BigDecimal("4.3"), 5515L, 90L,
				"up", 18, 25000L, 29500L,
				2L, 30600L, 26900L, 12,
				6L, 2L, OffsetDateTime.parse("2026-07-14T00:00:00Z"),
				OffsetDateTime.parse("2026-07-14T00:00:00Z"), new BigDecimal("2.4"));

		InfluencerDetailResponse response =
				assembler.toResponse(today, glowAccount(), glowCategoryStats(), glowSeries());

		assertThat(response.report().activity().lastUploadDaysAgo()).isEqualTo(0L);
		assertThat(response.report().activity().isActive()).isTrue();
		assertThat(response.report().ads().lastAdNote()).isEqualTo("마지막 광고 오늘");
	}

	@Test
	void 경과일_14일_경계에서_isActive가_갈린다() {
		AccountSummary fourteenDaysAgo = new AccountSummary("glow", 42555L, 312L, 486L, "건성 8년차",
				12L, 10L, "views", 30600L, new BigDecimal("1.3"),
				new BigDecimal("4.3"), 5515L, 90L,
				"up", 18, 25000L, 29500L,
				2L, 30600L, 26900L, 12,
				6L, 2L, OffsetDateTime.parse("2026-07-11T00:00:00Z"),
				OffsetDateTime.parse("2026-06-30T00:00:00Z"), new BigDecimal("2.4"));
		AccountSummary thirteenDaysAgo = new AccountSummary("glow", 42555L, 312L, 486L, "건성 8년차",
				12L, 10L, "views", 30600L, new BigDecimal("1.3"),
				new BigDecimal("4.3"), 5515L, 90L,
				"up", 18, 25000L, 29500L,
				2L, 30600L, 26900L, 12,
				6L, 2L, OffsetDateTime.parse("2026-07-11T00:00:00Z"),
				OffsetDateTime.parse("2026-07-01T00:00:00Z"), new BigDecimal("2.4"));

		InfluencerDetailResponse fourteen =
				assembler.toResponse(fourteenDaysAgo, glowAccount(), glowCategoryStats(), glowSeries());
		InfluencerDetailResponse thirteen =
				assembler.toResponse(thirteenDaysAgo, glowAccount(), glowCategoryStats(), glowSeries());

		assertThat(fourteen.report().activity().lastUploadDaysAgo()).isEqualTo(14L);
		assertThat(fourteen.report().activity().isActive()).isFalse();
		assertThat(thirteen.report().activity().lastUploadDaysAgo()).isEqualTo(13L);
		assertThat(thirteen.report().activity().isActive()).isTrue();
	}
}
