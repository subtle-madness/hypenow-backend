package com.celfit.was.influencer;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.AccountAnalysis;
import com.celfit.contract.analysis.AccountCategoryStat;
import com.celfit.contract.analysis.AccountContentPoint;
import com.celfit.contract.analysis.AccountSummary;
import com.celfit.was.config.SecurityConfig;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Security 도입 후 GET /api/** permitAll을 실제로 결정하는 건 SecurityConfig의 필터체인이라(예전 WebConfig의
// CORS 매핑은 걷어냄) 이 빈을 같이 import해야 401로 막히지 않는다 — 기대값은 그대로다.
@WebMvcTest(controllers = InfluencerDetailController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000,https://celfit-front.vercel.app")
@Import({InfluencerDetailAssembler.class, SecurityConfig.class})
class InfluencerDetailControllerTest {

	// 시스템 시계(ClockConfig)를 쓰면 픽스처 lastPostedAt(07-12) 경과일이 실행 날짜에 따라 변해
	// isActive 단언이 시한폭탄이 된다(2026-07-26부터 14일 경과로 false) — 어셈블러 테스트와 같은 고정 시각 주입.
	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		Clock clock() {
			return Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC);
		}
	}

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
				new Account("glow", "글로우", "https://pic/glow.jpg", 42555L, null)));
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
		given(repository.findLatestAnalysis("glow")).willReturn(Optional.of(
				new AccountAnalysis("glow", OffsetDateTime.parse("2026-07-13T00:00:00Z"), "opus",
						OffsetDateTime.parse("2026-07-12T00:00:00Z"), 12L,
						"건성 피부 신뢰 리뷰어", "AI 분석 요약 문단", "상승 흐름 유지 중", "릴스가 평균을 끌어올림",
						List.of("솔직 후기", "루틴 중심", "건성 특화"), "광고에서도 평균의 88%를 유지",
						"이틀에 한 번 꾸준한 페이스")));
	}

	@Test
	void 인플루언서_상세를_블록_JSON으로_반환한다() throws Exception {
		givenGlow();

		mockMvc.perform(get("/api/influencers/glow").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.profile.displayName").value("글로우"))
				.andExpect(jsonPath("$.report.stats.metric").value("views"))
				.andExpect(jsonPath("$.report.chart.bars[0].shortCode").value("g1"))
				.andExpect(jsonPath("$.report.ads.strip[1]").value(true))
				.andExpect(jsonPath("$.report.activity.isActive").value(true))
				.andExpect(jsonPath("$.report.tagline").value("건성 피부 신뢰 리뷰어"))
				.andExpect(jsonPath("$.report.summary").value("AI 분석 요약 문단"))
				.andExpect(jsonPath("$.report.trend.note").value("상승 흐름 유지 중"))
				.andExpect(jsonPath("$.report.chart.note").value("릴스가 평균을 끌어올림"))
				.andExpect(jsonPath("$.report.contentMix.traits[0]").value("솔직 후기"))
				.andExpect(jsonPath("$.report.ads.headline").value("광고에서도 평균의 88%를 유지"))
				.andExpect(jsonPath("$.report.activity.paceNote").value("이틀에 한 번 꾸준한 페이스"));
	}

	@Test
	void 분석_이력이_없으면_카피_필드는_null이다() throws Exception {
		givenGlow();
		given(repository.findLatestAnalysis("glow")).willReturn(Optional.empty());

		mockMvc.perform(get("/api/influencers/glow").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.report.tagline", org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.report.trend.note", org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.report.contentMix.traits", org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.report.ads.headline", org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.report.activity.paceNote", org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.report.stats.metric").value("views"));
	}

	@Test
	void 없는_handle이면_404() throws Exception {
		given(repository.findSummary("nope")).willReturn(Optional.empty());

		mockMvc.perform(get("/api/influencers/nope").with(user("tester")))
				.andExpect(status().isNotFound());
	}

	@Test
	void 허용_오리진에_CORS_헤더를_내린다() throws Exception {
		givenGlow();

		mockMvc.perform(get("/api/influencers/glow").with(user("tester"))
						.header("Origin", "https://celfit-front.vercel.app"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin",
						"https://celfit-front.vercel.app"));
	}
}
