package com.celfit.was.v1.influencer;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.BrandRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CategoryRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CopyRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.SeriesRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.SummaryRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

// 서비스가 리포지토리·조립을 캡슐화하므로 컨트롤러 테스트는 서비스만 목킹한다.
// 정상 케이스는 어셈블러를 테스트 안에서 직접 인스턴스화해(시스템 시계 — ClockConfig와 동일 관용구,
// 문구 검증은 V1InfluencerReportAssemblerTest 몫) 같은 스텁 입력으로 기대 리포트를 조립한다.
@WebMvcTest(controllers = V1InfluencerReportController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V1InfluencerReportControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	V1InfluencerReportService service;

	private final V1InfluencerReportAssembler assembler =
			new V1InfluencerReportAssembler(Clock.systemUTC(), new ObjectMapper());

	private SummaryRow fullSummary() {
		return new SummaryRow(24L, 321L, "views", 52000L, new BigDecimal("0.42"),
				new BigDecimal("3.10"), 1500L, 80L, "up", 6L,
				60000L, 42000L, 30, 18L, 6L,
				null, null, new BigDecimal("2.5"));
	}

	@Test
	void 성공_응답은_스펙_6_5_구조를_가진다() throws Exception {
		SummaryRow summary = fullSummary();
		CopyRow copy = new CopyRow("태그라인", "요약", "추세 노트", "차트 노트",
				"[\"뷰티\",\"유머\"]", "광고 헤드라인", "페이스 노트");
		List<SeriesRow> series = List.of(
				new SeriesRow(OffsetDateTime.parse("2026-06-30T20:30:00Z"), "reels", 1000L, 100L, 10L, false),
				new SeriesRow(OffsetDateTime.parse("2026-07-05T03:00:00Z"), "reels", 500L, 200L, 20L, true));
		List<CategoryRow> categories = List.of(new CategoryRow("메이크업", 5L));
		List<BrandRow> brands = List.of(new BrandRow("머지", 3L));
		given(service.report("zingdong__"))
				.willReturn(assembler.toReport(summary, copy, series, categories, brands));

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
		given(service.report("ghost")).willThrow(V1ApiException.notFound("인플루언서를 찾을 수 없습니다."));

		mockMvc.perform(get("/v1/influencers/ghost/ai-report").with(user("tester")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 카피_미생성이어도_200에_블록_구조는_유지된다() throws Exception {
		given(service.report("h"))
				.willReturn(assembler.toReport(fullSummary(), null, List.of(), List.of(), List.of()));

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
