package com.celfit.was;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * springdoc 스모크 — /v3/api-docs가 뜨고 /v1 표면만 담기는지(paths-to-match) 확인.
 * 스키마 상세는 검증하지 않는다 — 정본은 프론트 API 스펙 문서, Swagger는 보조 문서.
 */
@AutoConfigureMockMvc
class OpenApiDocsIntegrationTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void api_docs는_v1_표면만_문서화한다() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("hypenow API"))
				.andExpect(jsonPath("$.paths['/v1/contents']").exists())
				// 구 /api 표면·내부 페이지는 paths-to-match(/v1/**) 밖 — 문서에 없어야 한다
				.andExpect(jsonPath("$.paths['/api/contents']").doesNotExist());
	}
}
