package com.celfit.was.v1.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class V1ContentReportAssemblerTest {

	private final V1ContentReportAssembler assembler = new V1ContentReportAssembler(new ObjectMapper());

	@Test
	void multiple은_소수1자리_baseline_0이면_null() {
		assertThat(assembler.multiple(3307180L, 412L)).isEqualByComparingTo("8027.1");
		assertThat(assembler.multiple(100L, 0L)).isNull();
		assertThat(assembler.multiple(null, 412L)).isNull();
	}

	@Test
	void 피드는_engagementRate_value가_null() {
		assertThat(assembler.engagementRateValue(null, 24L, 2L)).isNull();
		assertThat(assembler.engagementRateValue(1000L, 24L, 2L)).isEqualByComparingTo("2.60");
	}

	@Test
	void 댓글_분포는_카테고리별_비율() {
		List<ContentAiReport.CommentAnalysis.Slice> d =
				assembler.distribution(Map.of("purchase", 1L, "friendTag", 1L));
		assertThat(d).extracting("category").containsExactlyInAnyOrder("purchase", "friendTag");
		assertThat(d).extracting("ratio").allMatch(r -> ((BigDecimal) r).compareTo(new BigDecimal("0.50")) == 0);
	}

	@Test
	void toReport는_스펙_6_3_구조로_조립한다() {
		var row = new V1ContentReportRepository.ReportRow(
				"SC1", "zingdong__", "reels", 3307180L, 42216L, 86L,
				"요약", "패턴 서술", "댓글 인사이트",
				412L, 1, 12,
				24, new BigDecimal("1.29"), 35000L, 120L,
				5, 250000L, 480L,
				"makeup", "[{\"name\":\"머지\",\"evidence\":\"로고 노출\"}]", "high",
				"[\"협찬 문구\"]", "명시", "[\"아이라이너\"]",
				"[{\"label\":\"톤\",\"value\":\"쿨톤\"}]",
				"good", "실구매 후기 다수", "메이크업");
		var reels = List.of(new V1ContentReportRepository.ReelPointRow(
				"SC1", 1000L, OffsetDateTime.parse("2026-06-30T20:30:00Z"))); // KST 2026-07-01 05:30
		var comments = List.of(new V1ContentReportRepository.CommentRow(
				7L, "u***", "좋아요", 5L, "purchase"));

		ContentAiReport report = assembler.toReport(row, reels,
				Map.of("purchase", 3L, "adAware", 1L), comments);

		assertThat(report.scope().basis()).isEqualTo("recent-posts");
		assertThat(report.scope().analyzedCount()).isEqualTo(24L);
		assertThat(report.summary()).isEqualTo("요약");
		// 라이브 재계산: baseline=avg(1000)=1000, multiple=3307180/1000
		assertThat(report.comparison().views().multiple()).isEqualByComparingTo("3307.2");
		assertThat(report.comparison().views().recentReels())
				.containsExactly(new ContentAiReport.Comparison.Views.ReelPoint("SC1", 1000L, "2026-07-01", true));
		assertThat(report.comparison().engagementQuality().likes().baselineCount()).isEqualTo(35000L);
		assertThat(report.comparison().narrative()).isEqualTo("패턴 서술");
		assertThat(report.categoryContext().categoryLabel()).isEqualTo("메이크업");
		assertThat(report.vlmAnalysis().brands())
				.containsExactly(new ContentAiReport.VlmAnalysis.Brand("머지", "로고 노출"));
		assertThat(report.vlmAnalysis().sponsoredSignal().level()).isEqualTo("high");
		assertThat(report.vlmAnalysis().attributes())
				.containsExactly(new ContentAiReport.VlmAnalysis.Attribute("톤", "쿨톤"));
		assertThat(report.commentAnalysis().signals().adAversionRate()).isEqualByComparingTo("0.25");
		assertThat(report.commentAnalysis().signals().friendTagRate()).isEqualByComparingTo("0.00");
		assertThat(report.commentAnalysis().signals().authenticity().grade()).isEqualTo("good");
		assertThat(report.comments()).containsExactly(
				new ContentAiReport.Comment("7", "u***", "좋아요", 5L, "purchase"));
	}

	@Test
	void vlm_미실행이어도_구조는_유지된다() {
		var row = new V1ContentReportRepository.ReportRow(
				"SC2", "handle", "feed", null, 100L, 10L,
				null, null, null,
				null, null, null,
				null, null, null, null,
				null, null, null,
				null, null, null, null, null, null, null,
				null, null, null);

		ContentAiReport report = assembler.toReport(row, List.of(), Map.of(), List.of());

		// 피드: views null → 배수·참여율 value도 null
		assertThat(report.comparison().views().value()).isNull();
		assertThat(report.comparison().views().multiple()).isNull();
		assertThat(report.comparison().engagementRate().value()).isNull();
		// jsonb null 방어 — 빈 배열, 구조 유지
		assertThat(report.vlmAnalysis().brands()).isEmpty();
		assertThat(report.vlmAnalysis().sponsoredSignal().reasons()).isEmpty();
		assertThat(report.vlmAnalysis().productCategories()).isEmpty();
		assertThat(report.vlmAnalysis().attributes()).isEmpty();
		// 분류 0건 → 분포 빈 배열, 시그널 0.00
		assertThat(report.commentAnalysis().distribution()).isEmpty();
		assertThat(report.commentAnalysis().signals().adAversionRate()).isEqualByComparingTo("0.00");
		assertThat(report.commentAnalysis().signals().friendTagRate()).isEqualByComparingTo("0.00");
	}

	@Test
	void comparisonViews_라이브_재계산_프리즈컬럼_무시_식별자_채움() {
		var row = reportRowWithViews("sc1", 30405L, /* frozenBaseline */ 999L, /* frozenRank */ 9, /* frozenCount */ 9);
		var reels = List.of(
				new V1ContentReportRepository.ReelPointRow("sc0", 5040L, odt("2026-07-08")),
				new V1ContentReportRepository.ReelPointRow("sc1", 30405L, odt("2026-07-14")),
				new V1ContentReportRepository.ReelPointRow("sc2", null, odt("2026-07-17")));

		ContentAiReport report = assembler.toReport(row, reels, Map.of(), List.of());
		var v = report.comparison().views();

		assertThat(v.value()).isEqualTo(30405L);
		assertThat(v.baseline()).isEqualTo(17723L);
		assertThat(v.recentCount()).isEqualTo(2);
		assertThat(v.rankInRecent()).isEqualTo(1);
		assertThat(v.multiple()).isEqualByComparingTo("1.7");
		assertThat(v.recentReels()).extracting("contentId").containsExactly("sc0", "sc1", "sc2");
		assertThat(v.recentReels()).extracting("isCurrent").containsExactly(false, true, false);
		assertThat(v.recentReels().get(2).views()).isNull();
	}

	/** ReportRow 축약 생성 — shortCode/views/frozen 컬럼(baseline·rank·count)만 지정, 나머지는 null·0. */
	private V1ContentReportRepository.ReportRow reportRowWithViews(
			String shortCode, Long views, Long frozenBaseline, Integer frozenRank, Integer frozenCount) {
		return new V1ContentReportRepository.ReportRow(
				shortCode, "handle", "reels", views, 0L, 0L,
				null, null, null,
				frozenBaseline, frozenRank, frozenCount,
				null, null, null, null,
				null, null, null,
				null, null, null, null, null, null, null,
				null, null, null);
	}

	private OffsetDateTime odt(String isoDate) {
		return OffsetDateTime.parse(isoDate + "T00:00:00Z");
	}
}
