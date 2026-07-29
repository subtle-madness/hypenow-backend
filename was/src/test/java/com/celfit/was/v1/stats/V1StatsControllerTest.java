package com.celfit.was.v1.stats;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.contract.analysis.LandingStats;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 기존 v1 컨트롤러 테스트와 같은 구성 — 인증 없이 호출해 Public(permitAll)임을 함께 확인한다.
@WebMvcTest(controllers = V1StatsController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V1StatsControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	V1StatsRepository repository;

	// 4구간 모수(65명 = 10/22/25/8, 콘텐츠 66) — followers500to3k는 record 마지막 컴포넌트(V43 expand).
	private LandingStats stats() {
		return new LandingStats(66L, 65L, 17_453_444L, 484_818L, 22L, 25L, 8L,
				OffsetDateTime.parse("2026-07-17T04:30:00Z"), 10L);
	}

	@Test
	void 성공_응답은_스펙_6_20_구조를_가진다() throws Exception {
		given(repository.find()).willReturn(Optional.of(stats()));

		mockMvc.perform(get("/v1/stats"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.contentsCount").value(66))
				.andExpect(jsonPath("$.data.influencersCount").value(65))
				.andExpect(jsonPath("$.data.totalViews").value(17453444))
				.andExpect(jsonPath("$.data.avgViews").value(484818))
				.andExpect(jsonPath("$.data.followerDistribution[0].range").value("500-3k"))
				.andExpect(jsonPath("$.data.followerDistribution[0].pct").value(15))
				.andExpect(jsonPath("$.data.followerDistribution[1].range").value("3k-10k"))
				.andExpect(jsonPath("$.data.followerDistribution[1].pct").value(34))
				.andExpect(jsonPath("$.data.followerDistribution[2].range").value("10k-30k"))
				.andExpect(jsonPath("$.data.followerDistribution[2].pct").value(39))
				.andExpect(jsonPath("$.data.followerDistribution[3].range").value("30k-50k"))
				.andExpect(jsonPath("$.data.followerDistribution[3].pct").value(12))
				.andExpect(jsonPath("$.data.updatedAt").value("2026-07-17T04:30:00Z"));
	}

	@Test
	void 주간_갱신_전제의_강한_캐시_헤더를_준다() throws Exception {
		given(repository.find()).willReturn(Optional.of(stats()));

		mockMvc.perform(get("/v1/stats"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "max-age=3600, public"));
	}

	@Test
	void 미러_미실행이면_NOT_FOUND() throws Exception {
		given(repository.find()).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/stats"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}
}
