package com.celfit.was.v1.influencer;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.ClockConfig;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.BrandRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CategoryRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CopyRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.SeriesRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.SummaryRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// V1ContentControllerTest와 같은 구성 — Clock은 ClockConfig(시스템 시계, 문구 검증은 어셈블러 테스트 몫).
@WebMvcTest(controllers = V1InfluencerReportController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V1InfluencerReportAssembler.class, ClockConfig.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1InfluencerReportControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	V1InfluencerReportRepository repository;

	// TODO(Task 9): 신 7-인자 시그니처에 맞춘 기계적 컴파일 수정 — 픽스처·jsonPath 갱신은 컨트롤러 배선 태스크 몫.
	private SummaryRow fullSummary() {
		return new SummaryRow(10000L, 24L, 321L, "views", 52000L, new BigDecimal("0.42"),
				new BigDecimal("3.10"), 1500L, 80L, null, new BigDecimal("2.5"));
	}

	@Test
	void 성공_응답은_스펙_6_5_구조를_가진다() throws Exception {
		given(repository.findSummary("zingdong__")).willReturn(Optional.of(fullSummary()));
		given(repository.findLatestCopy("zingdong__")).willReturn(Optional.of(
				new CopyRow("태그라인", "[\"뷰티\",\"유머\"]", "성과 요약", "콘텐츠 요약", "광고 요약")));
		given(repository.findSeries("zingdong__")).willReturn(List.of(
				new SeriesRow(OffsetDateTime.parse("2026-06-30T20:30:00Z"), "reels", 1000L, 100L, 10L, false,
						null, null, null),
				new SeriesRow(OffsetDateTime.parse("2026-07-05T03:00:00Z"), "reels", 500L, 200L, 20L, true,
						null, null, null)));
		given(repository.findCategories("zingdong__")).willReturn(List.of(new CategoryRow("메이크업", 5L)));
		given(repository.findBrands("zingdong__")).willReturn(List.of(new BrandRow("머지", 3L)));

		mockMvc.perform(get("/v1/influencers/zingdong__/ai-report").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.tagline").value("태그라인"))
				.andExpect(jsonPath("$.data.analyzedCount").value(24))
				.andExpect(jsonPath("$.data.totalPosts").value(321))
				.andExpect(jsonPath("$.data.stats.metric").value("views"))
				.andExpect(jsonPath("$.data.stats.avgEr").value(3.10))
				.andExpect(jsonPath("$.data.trend.direction").value("up"))
				.andExpect(jsonPath("$.data.chart.metric").value("views"))
				.andExpect(jsonPath("$.data.chart.bars").isArray())
				.andExpect(jsonPath("$.data.chart.bars[0].postedAt").value("2026-07-01"))
				.andExpect(jsonPath("$.data.chart.bars[1].sponsored").value(true))
				.andExpect(jsonPath("$.data.contentMix.categories[0].label").value("메이크업"))
				.andExpect(jsonPath("$.data.contentMix.traits[0]").value("뷰티"))
				.andExpect(jsonPath("$.data.ads.strip").isArray())
				.andExpect(jsonPath("$.data.ads.strip[0]").value(false))
				.andExpect(jsonPath("$.data.ads.strip[1]").value(true))
				.andExpect(jsonPath("$.data.ads.comparison.dropPct").value(50)) // organic 1000 vs 광고 500
				.andExpect(jsonPath("$.data.ads.brands[0].name").value("머지"))
				.andExpect(jsonPath("$.data.activity.isActive").value(false)); // 업로드 이력 없음
	}

	@Test
	void 없는_인플루언서는_NOT_FOUND() throws Exception {
		given(repository.findSummary("ghost")).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/influencers/ghost/ai-report").with(user("tester")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 카피_미생성이어도_200에_블록_구조는_유지된다() throws Exception {
		given(repository.findSummary("h")).willReturn(Optional.of(fullSummary()));
		given(repository.findLatestCopy("h")).willReturn(Optional.empty());
		given(repository.findSeries("h")).willReturn(List.of());
		given(repository.findCategories("h")).willReturn(List.of());
		given(repository.findBrands("h")).willReturn(List.of());

		mockMvc.perform(get("/v1/influencers/h/ai-report").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.tagline").doesNotExist())
				.andExpect(jsonPath("$.data.summary").doesNotExist())
				.andExpect(jsonPath("$.data.trend.direction").value("up"))
				.andExpect(jsonPath("$.data.trend.note").doesNotExist())
				.andExpect(jsonPath("$.data.chart.bars").isEmpty())
				.andExpect(jsonPath("$.data.contentMix.traits").isArray())
				.andExpect(jsonPath("$.data.contentMix.traits").isEmpty())
				.andExpect(jsonPath("$.data.ads.headline").doesNotExist())
				.andExpect(jsonPath("$.data.activity.paceNote").doesNotExist());
	}
}
