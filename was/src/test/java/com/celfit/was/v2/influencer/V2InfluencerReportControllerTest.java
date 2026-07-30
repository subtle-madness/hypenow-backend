package com.celfit.was.v2.influencer;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.ClockConfig;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryAssembler;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.CardRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.CopyRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.SummaryRow;
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

/** 스펙 6.22·6.23 배선 — 공개 표면(로그인 월 화이트리스트, SecurityConfig 참고). */
@WebMvcTest(controllers = V2InfluencerReportController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V2InfluencerReportAssembler.class, V1InfluencerDiscoveryAssembler.class,
		ClockConfig.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V2InfluencerReportControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	V2InfluencerReportRepository repository;

	@MockitoBean
	V1InfluencerDiscoveryRepository discoveryRepository;

	// V2InfluencerReportAssemblerTest와 동일 픽스처
	private SummaryRow fullSummary() {
		return new SummaryRow(10_000L, 12L, 214L, 52_000L, new BigDecimal("5.2"),
				new BigDecimal("3.14"), 1_500L, 80L,
				OffsetDateTime.parse("2026-07-23T00:00:00Z"), new BigDecimal("6.2"));
	}

	private CopyRow copy() {
		return new CopyRow("태그라인", "[\"정보형\",\"스킨케어\"]", "성과 요약", "콘텐츠 요약", "광고 요약");
	}

	@Test
	void 카피_미생성이면_404_리포트_미생성() throws Exception {
		given(repository.findSummary("haeun.log")).willReturn(Optional.of(fullSummary()));
		given(repository.findLatestCopy("haeun.log")).willReturn(Optional.empty());
		mockMvc.perform(get("/v2/influencers/haeun.log/ai-report"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 인플루언서_없으면_404() throws Exception {
		given(repository.findSummary("ghost")).willReturn(Optional.empty());
		mockMvc.perform(get("/v2/influencers/ghost/ai-report"))
				.andExpect(status().isNotFound());
	}

	@Test
	void 리포트_v2_응답_구조() throws Exception {
		given(repository.findSummary("haeun.log")).willReturn(Optional.of(fullSummary()));
		given(repository.findLatestCopy("haeun.log")).willReturn(Optional.of(copy()));
		given(repository.findSeries("haeun.log")).willReturn(List.of());
		given(repository.findCategories("haeun.log")).willReturn(List.of());
		given(repository.findBrandCollabs("haeun.log")).willReturn(List.of());

		mockMvc.perform(get("/v2/influencers/haeun.log/ai-report"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.perfSummary").value("성과 요약"))
				.andExpect(jsonPath("$.data.overall.views.value").isNumber())
				.andExpect(jsonPath("$.data.overall.sampleCount").value(12))
				.andExpect(jsonPath("$.data.ads.sponsoredCount").isNumber())
				.andExpect(jsonPath("$.data.stats").doesNotExist()) // v1 구조 부재 확인
				.andExpect(jsonPath("$.data.chart").doesNotExist());
	}

	@Test
	void 유사_인플루언서는_6_21_카드를_유사도_순으로() throws Exception {
		given(repository.findSummary("haeun.log")).willReturn(Optional.of(fullSummary()));
		given(repository.findSimilarHandles("haeun.log")).willReturn(List.of("b", "a"));
		// discoveryRepository.findCardsByHandles가 ["a","b"] 순(비유사도 순)으로 돌려줘도 응답은 ["b","a"]
		given(discoveryRepository.findCardsByHandles(List.of("b", "a"))).willReturn(List.of(
				new CardRow("a", "A", null, 1000L, 10L, 5L, "bio-a", null, null, null, null, null, null, 77L, 0L, null,
						new BigDecimal("77.7000")),
				new CardRow("b", "B", null, 2000L, 20L, 6L, "bio-b", null, null, null, null, null, null, null, 0L, null,
						null)));
		given(discoveryRepository.findShares(List.of("b", "a"))).willReturn(List.of());
		given(discoveryRepository.findBrands(List.of("b", "a"))).willReturn(List.of());
		given(discoveryRepository.findThumbs(List.of("b", "a"))).willReturn(List.of());
		given(discoveryRepository.findEngagements(List.of("b", "a"))).willReturn(List.of());

		mockMvc.perform(get("/v2/influencers/haeun.log/similar"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].id").value("b"))
				.andExpect(jsonPath("$.data[1].id").value("a"))
				// hypeScore는 2026-07-30부터 소수(스펙 §10) — avgHypeScorePrecise(77.7)를 그대로 싣는다.
				.andExpect(jsonPath("$.data[1].hypeScore").value(77.7));
	}

	@Test
	void 유사_기준_계정_없으면_404() throws Exception {
		given(repository.findSummary("ghost")).willReturn(Optional.empty());
		mockMvc.perform(get("/v2/influencers/ghost/similar"))
				.andExpect(status().isNotFound());
	}
}
