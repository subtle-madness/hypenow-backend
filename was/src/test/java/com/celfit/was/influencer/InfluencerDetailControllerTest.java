package com.celfit.was.influencer;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.AccountCategoryStat;
import com.celfit.contract.analysis.AccountContentPoint;
import com.celfit.contract.analysis.AccountSummary;
import com.celfit.was.config.ClockConfig;
import com.celfit.was.config.WebConfig;
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

@WebMvcTest(controllers = InfluencerDetailController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000,https://celfit-front.vercel.app")
@Import({InfluencerDetailAssembler.class, ClockConfig.class, WebConfig.class})
class InfluencerDetailControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	InfluencerDetailRepository repository;

	private void givenGlow() {
		given(repository.findSummary("glow")).willReturn(Optional.of(
				new AccountSummary("glow", 42555L, 312L, 486L, "건성 8년차",
						12L, 10L, "views", 30600L, new BigDecimal("1.3"),
						new BigDecimal("4.3"), 5515L, 90L,
						"up", 18, 25000L, 29500L,
						2L, 30600L, 26900L, 12,
						6L, 2L, OffsetDateTime.parse("2026-07-11T00:00:00Z"),
						OffsetDateTime.parse("2026-07-12T00:00:00Z"), new BigDecimal("2.4"))));
		given(repository.findAccount("glow")).willReturn(Optional.of(
				new Account("glow", "글로우", "https://pic/glow.jpg", 42555L)));
		given(repository.findCategoryStats("glow")).willReturn(List.of(
				new AccountCategoryStat("glow", "스킨케어", 7L),
				new AccountCategoryStat("glow", "메이크업", 3L),
				new AccountCategoryStat("glow", "클렌징", 3L)));
		given(repository.findSeries("glow")).willReturn(List.of(
				new AccountContentPoint("g1", "glow", OffsetDateTime.parse("2026-07-01T00:00:00Z"),
						"reels", 1000L, 100L, 10L, false),
				new AccountContentPoint("g2", "glow", OffsetDateTime.parse("2026-07-05T00:00:00Z"),
						"feed", null, 300L, 30L, true),
				new AccountContentPoint("g3", "glow", OffsetDateTime.parse("2026-07-08T00:00:00Z"),
						"reels", 2000L, 150L, 15L, false)));
	}

	@Test
	void 인플루언서_상세를_블록_JSON으로_반환한다() throws Exception {
		givenGlow();

		mockMvc.perform(get("/api/influencers/glow"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.profile.displayName").value("글로우"))
				.andExpect(jsonPath("$.report.stats.metric").value("views"))
				.andExpect(jsonPath("$.report.chart.bars[0].shortCode").value("g1"))
				.andExpect(jsonPath("$.report.ads.strip[1]").value(true))
				.andExpect(jsonPath("$.report.activity.isActive").value(true));
	}

	@Test
	void 없는_handle이면_404() throws Exception {
		given(repository.findSummary("nope")).willReturn(Optional.empty());

		mockMvc.perform(get("/api/influencers/nope"))
				.andExpect(status().isNotFound());
	}

	@Test
	void 허용_오리진에_CORS_헤더를_내린다() throws Exception {
		givenGlow();

		mockMvc.perform(get("/api/influencers/glow")
						.header("Origin", "https://celfit-front.vercel.app"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin",
						"https://celfit-front.vercel.app"));
	}
}
