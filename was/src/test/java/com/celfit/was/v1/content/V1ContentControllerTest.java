package com.celfit.was.v1.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.PagePrefetcher;
import com.celfit.was.v1.common.SavedLookup;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.content.V1ContentPageService.ContentPage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 계획서의 WebConfig는 SecurityConfig로 CORS가 일원화되며 삭제됨 — V1ExceptionAdviceTest와 같은 구성.
@WebMvcTest(controllers = V1ContentController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({ContentCardAssembler.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1ContentControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	V1ContentPageService pageService;

	@MockitoBean
	PagePrefetcher prefetcher;

	@MockitoBean
	SavedLookup savedLookup;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	private static ContentCardRow row(String code) {
		return new ContentCardRow(code, "https://thumb/" + code, "캡션",
				OffsetDateTime.parse("2026-07-02T03:00:00Z"), "reels", new BigDecimal("20"),
				"https://ig/" + code, 1000L, 100L, 10L, 500L,
				OffsetDateTime.parse("2026-07-05T03:00:00Z"), "makeup", null, "organic", null, null, null,
				"alpha", "알파", "https://pic/alpha.jpg", 5000L);
	}

	// 로그인 월(07-17) — 읽기도 인증 필수라 "비로그인 개인화 필드 없음" 계약은 401로 대체됐다
	@Test
	void 비로그인은_401_UNAUTHORIZED_envelope다() throws Exception {
		mockMvc.perform(get("/v1/contents")
						.param("startDate", "2026-07-05").param("endDate", "2026-07-11"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void 로그인이면_저장_여부로_isContentsSaved를_채운다() throws Exception {
		given(pageService.page(any())).willReturn(new ContentPage(List.of(row("c1"), row("c2")), 2L));
		given(pageService.distributorOptions()).willReturn(List.of());
		given(savedLookup.savedShortCodes(7L)).willReturn(Set.of("c1"));

		mockMvc.perform(get("/v1/contents").with(user(principal()))
						.param("startDate", "2026-07-05").param("endDate", "2026-07-11"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].id").value("c1"))
				.andExpect(jsonPath("$.data[0].isContentsSaved").value(true))
				.andExpect(jsonPath("$.data[1].id").value("c2"))
				.andExpect(jsonPath("$.data[1].isContentsSaved").value(false));
	}

	@Test
	void 성공_응답은_envelope와_meta를_가진다() throws Exception {
		given(pageService.page(any())).willReturn(new ContentPage(List.of(), 0L));
		given(pageService.distributorOptions())
				.willReturn(List.of(Map.of("id", "daiso", "name", "다이소")));

		mockMvc.perform(get("/v1/contents").with(user(principal()))
						.param("startDate", "2026-07-05").param("endDate", "2026-07-11"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.meta.total").value(0))
				.andExpect(jsonPath("$.meta.limit").value(100))
				.andExpect(jsonPath("$.meta.offset").value(0))
				.andExpect(jsonPath("$.meta.distributors[0].id").value("daiso"));
	}

	@Test
	void offset_파라미터는_meta에_그대로_반영된다() throws Exception {
		given(pageService.page(any())).willReturn(new ContentPage(List.of(), 0L));
		given(pageService.distributorOptions()).willReturn(List.of());

		mockMvc.perform(get("/v1/contents").with(user(principal()))
						.param("startDate", "2026-07-05").param("endDate", "2026-07-11")
						.param("offset", "20"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.offset").value(20));
	}

	// 프리페치 배선(I1) — 응답 반환 후 다음 페이지를 pageService에 선요청하는지, 그 쿼리가 offset만
	// 전진했는지를 검증한다. query record가 캐시 키를 겸하므로 매핑 실수는 곧 캐시 공유 사고다(M6).
	@Test
	void 다음_페이지가_있으면_프리페치가_다음_쿼리로_page를_호출한다() throws Exception {
		List<ContentCardRow> rows = IntStream.range(0, 50).mapToObj(i -> row("c" + i)).toList();
		given(pageService.page(any())).willReturn(new ContentPage(rows, 200L));
		given(pageService.distributorOptions()).willReturn(List.of());

		mockMvc.perform(get("/v1/contents").with(user(principal()))
						.param("startDate", "2026-07-05").param("endDate", "2026-07-11")
						.param("limit", "50").param("contentType", "feed"))
				.andExpect(status().isOk());

		ArgumentCaptor<Runnable> prefetchTask = ArgumentCaptor.forClass(Runnable.class);
		verify(prefetcher).prefetch(prefetchTask.capture());
		prefetchTask.getValue().run();

		ArgumentCaptor<V1ContentQuery> query = ArgumentCaptor.forClass(V1ContentQuery.class);
		verify(pageService, times(2)).page(query.capture());
		// 1차 호출 — 요청 파라미터가 쿼리(=캐시 키)에 정확히 반영됐는지.
		assertThat(query.getAllValues().get(0).limit()).isEqualTo(50);
		assertThat(query.getAllValues().get(0).contentType()).isEqualTo("feed");
		assertThat(query.getAllValues().get(0).offset()).isEqualTo(0);
		// 2차 호출(프리페치) — offset만 limit만큼 전진.
		assertThat(query.getAllValues().get(1).offset()).isEqualTo(50);
		assertThat(query.getAllValues().get(1).limit()).isEqualTo(50);
	}

	@Test
	void 마지막_페이지면_프리페치하지_않는다() throws Exception {
		given(pageService.page(any())).willReturn(new ContentPage(List.of(row("c1")), 1L));
		given(pageService.distributorOptions()).willReturn(List.of());

		mockMvc.perform(get("/v1/contents").with(user(principal()))
						.param("startDate", "2026-07-05").param("endDate", "2026-07-11")
						.param("limit", "50"))
				.andExpect(status().isOk());

		verify(prefetcher, never()).prefetch(any());
	}

	@Test
	void 음수_offset은_VALIDATION_FAILED() throws Exception {
		mockMvc.perform(get("/v1/contents").with(user(principal()))
						.param("startDate", "2026-07-05").param("endDate", "2026-07-11")
						.param("offset", "-1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void startDate가_endDate보다_뒤면_VALIDATION_FAILED() throws Exception {
		mockMvc.perform(get("/v1/contents").with(user(principal()))
						.param("startDate", "2026-07-12").param("endDate", "2026-07-11"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 잘못된_enum은_VALIDATION_FAILED() throws Exception {
		mockMvc.perform(get("/v1/contents").with(user(principal()))
						.param("startDate", "2026-07-05").param("endDate", "2026-07-11")
						.param("sort", "hot"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void startDate_누락은_VALIDATION_FAILED() throws Exception {
		mockMvc.perform(get("/v1/contents").with(user(principal())).param("endDate", "2026-07-11"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	// /v1 표면 CORS 회귀 — SecurityConfig가 /api/**만 등록해 /v1/**에 CORS 헤더가 안 나가던 누락 방지.
	@Test
	void 허용_오리진에_CORS_헤더를_내린다() throws Exception {
		given(pageService.page(any())).willReturn(new ContentPage(List.of(), 0L));
		given(pageService.distributorOptions()).willReturn(List.of());

		mockMvc.perform(get("/v1/contents").with(user(principal()))
						.param("startDate", "2026-07-05").param("endDate", "2026-07-11")
						.header("Origin", "http://localhost:3000"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
	}

	@Test
	void 비허용_오리진은_CORS로_차단된다() throws Exception {
		mockMvc.perform(get("/v1/contents")
						.param("startDate", "2026-07-05").param("endDate", "2026-07-11")
						.header("Origin", "https://evil.example.com"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	void limit_상한_초과는_VALIDATION_FAILED() throws Exception {
		// (스텁 불필요 — 컨트롤러 진입 전 검증 실패)
		mockMvc.perform(get("/v1/contents").with(user(principal()))
						.param("startDate", "2026-07-05").param("endDate", "2026-07-11")
						.param("limit", "101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}
}
