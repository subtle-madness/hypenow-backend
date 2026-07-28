package com.celfit.was.v1.influencer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.CardRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = V1InfluencerDiscoveryController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V1InfluencerDiscoveryAssembler.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1InfluencerDiscoveryControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	V1InfluencerDiscoveryRepository repository;

	private static CardRow row(String handle) {
		return new CardRow(handle, "글로우", "/img/p/glow.jpg", 20000L, 214L, 380L,
				"소개", "저자극 톤", new BigDecimal("12.4"), new BigDecimal("3.8"),
				413200L, 10370L, 152L, 3L);
	}

	@Test
	void 익명_200_카드와_meta() throws Exception {
		given(repository.findCards(any())).willReturn(List.of(row("glow")));
		given(repository.countCards(any())).willReturn(109L);
		given(repository.findShares(anyList())).willReturn(List.of());
		given(repository.findBrands(anyList())).willReturn(List.of());
		given(repository.findThumbs(anyList())).willReturn(List.of());
		given(repository.findEngagements(anyList())).willReturn(List.of());

		mockMvc.perform(get("/v1/influencers?sponsored=1-2&offset=100"))
				.andExpect(status().isOk()) // 로그인 월 예외(permitAll) — 비로그인 공개 페이지
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].id").value("glow"))
				.andExpect(jsonPath("$.data[0].handle").value("glow"))
				.andExpect(jsonPath("$.data[0].email").value((String) null)) // null 노출(부재 아님)
				.andExpect(jsonPath("$.data[0].reachMultiplier").value(12.4))
				.andExpect(jsonPath("$.data[0].collaboratedBrands").isArray())
				.andExpect(jsonPath("$.error").value((String) null))
				.andExpect(jsonPath("$.meta.total").value(109))
				.andExpect(jsonPath("$.meta.limit").value(100))
				.andExpect(jsonPath("$.meta.offset").value(100));

		ArgumentCaptor<List<String>> handles = ArgumentCaptor.captor();
		Mockito.verify(repository).findThumbs(handles.capture());
		org.assertj.core.api.Assertions.assertThat(handles.getValue()).containsExactly("glow");
	}

	@Test
	void 잘못된_enum은_400_VALIDATION_FAILED() throws Exception {
		mockMvc.perform(get("/v1/influencers?sort=hype"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		mockMvc.perform(get("/v1/influencers?offset=-1"))
				.andExpect(status().isBadRequest());
	}
}
