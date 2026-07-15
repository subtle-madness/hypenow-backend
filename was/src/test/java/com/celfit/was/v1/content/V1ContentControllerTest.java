package com.celfit.was.v1.content;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
	V1ContentRepository repository;

	@Test
	void 성공_응답은_envelope와_meta를_가진다() throws Exception {
		given(repository.findCards(any())).willReturn(List.of());
		given(repository.countCards(any())).willReturn(0L);
		given(repository.findDistributorOptions())
				.willReturn(List.of(Map.of("id", "daiso", "name", "다이소")));

		mockMvc.perform(get("/v1/contents")
						.param("startDate", "2026-07-05").param("endDate", "2026-07-11"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.meta.total").value(0))
				.andExpect(jsonPath("$.meta.limit").value(100))
				.andExpect(jsonPath("$.meta.distributors[0].id").value("daiso"));
	}

	@Test
	void startDate가_endDate보다_뒤면_VALIDATION_FAILED() throws Exception {
		mockMvc.perform(get("/v1/contents")
						.param("startDate", "2026-07-12").param("endDate", "2026-07-11"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 잘못된_enum은_VALIDATION_FAILED() throws Exception {
		mockMvc.perform(get("/v1/contents")
						.param("startDate", "2026-07-05").param("endDate", "2026-07-11")
						.param("sort", "hot"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void startDate_누락은_VALIDATION_FAILED() throws Exception {
		mockMvc.perform(get("/v1/contents").param("endDate", "2026-07-11"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	// /v1 표면 CORS 회귀 — SecurityConfig가 /api/**만 등록해 /v1/**에 CORS 헤더가 안 나가던 누락 방지.
	@Test
	void 허용_오리진에_CORS_헤더를_내린다() throws Exception {
		given(repository.findCards(any())).willReturn(List.of());
		given(repository.countCards(any())).willReturn(0L);
		given(repository.findDistributorOptions()).willReturn(List.of());

		mockMvc.perform(get("/v1/contents")
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
		mockMvc.perform(get("/v1/contents")
						.param("startDate", "2026-07-05").param("endDate", "2026-07-11")
						.param("limit", "101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}
}
