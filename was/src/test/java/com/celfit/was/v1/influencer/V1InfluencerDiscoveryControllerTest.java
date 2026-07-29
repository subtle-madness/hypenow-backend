package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.PagePrefetcher;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryPageService.DiscoveryPage;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.CardRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = V1InfluencerDiscoveryController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V1InfluencerDiscoveryControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	V1InfluencerDiscoveryPageService pageService;

	@MockitoBean
	PagePrefetcher prefetcher;

	private static CardRow row(String handle) {
		return new CardRow(handle, "글로우", "/img/p/glow.jpg", 20000L, 214L, 380L,
				"소개", "저자극 톤", new BigDecimal("12.4"), new BigDecimal("3.8"),
				413200L, 10370L, 152L, 3L);
	}

	@Test
	void 익명_200_카드와_meta() throws Exception {
		List<InfluencerCard> cards = new V1InfluencerDiscoveryAssembler()
				.toCards(List.of(row("glow")), List.of(), List.of(), List.of(), List.of());
		given(pageService.page(any())).willReturn(new DiscoveryPage(cards, 109L));

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

	// 프리페치 배선(I1) — 응답 반환 후 다음 페이지를 pageService에 선요청하는지, 그 쿼리가 offset만
	// 전진했는지를 검증한다. query record가 캐시 키를 겸하므로 매핑 실수는 곧 캐시 공유 사고다(M6).
	@Test
	void 다음_페이지가_있으면_프리페치가_다음_쿼리로_page를_호출한다() throws Exception {
		List<InfluencerCard> cards = new V1InfluencerDiscoveryAssembler()
				.toCards(IntStream.range(0, 50).mapToObj(i -> row("glow" + i)).toList(),
						List.of(), List.of(), List.of(), List.of());
		given(pageService.page(any())).willReturn(new DiscoveryPage(cards, 200L));

		mockMvc.perform(get("/v1/influencers").param("limit", "50").param("mainCategory", "makeup"))
				.andExpect(status().isOk());

		ArgumentCaptor<Runnable> prefetchTask = ArgumentCaptor.forClass(Runnable.class);
		verify(prefetcher).prefetch(prefetchTask.capture());
		prefetchTask.getValue().run();

		ArgumentCaptor<V1InfluencerDiscoveryQuery> query =
				ArgumentCaptor.forClass(V1InfluencerDiscoveryQuery.class);
		verify(pageService, times(2)).page(query.capture());
		// 1차 호출 — 요청 파라미터가 쿼리(=캐시 키)에 정확히 반영됐는지.
		assertThat(query.getAllValues().get(0).limit()).isEqualTo(50);
		assertThat(query.getAllValues().get(0).mainCategory()).isEqualTo("makeup");
		assertThat(query.getAllValues().get(0).offset()).isEqualTo(0);
		// 2차 호출(프리페치) — offset만 limit만큼 전진.
		assertThat(query.getAllValues().get(1).offset()).isEqualTo(50);
		assertThat(query.getAllValues().get(1).limit()).isEqualTo(50);
	}

	@Test
	void 마지막_페이지면_프리페치하지_않는다() throws Exception {
		List<InfluencerCard> cards = new V1InfluencerDiscoveryAssembler()
				.toCards(List.of(row("glow")), List.of(), List.of(), List.of(), List.of());
		given(pageService.page(any())).willReturn(new DiscoveryPage(cards, 1L));

		mockMvc.perform(get("/v1/influencers").param("limit", "50"))
				.andExpect(status().isOk());

		verify(prefetcher, never()).prefetch(any());
	}
}
