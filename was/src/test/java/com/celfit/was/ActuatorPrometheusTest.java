package com.celfit.was;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 액추에이터 프로메테우스 노출 검증 — ① 인증 없이 200(permitAll 체인),
 * ② http.server.requests 히스토그램 버킷 노출(p95/p99 산출의 전제).
 *
 * <p>테스트 프로파일은 관리 포트를 분리하지 않으므로(관리 포트 분리는 application-prod.yml만)
 * 메인 포트의 /actuator/prometheus를 MockMvc로 직접 친다 — 시큐리티 체인은 포트와 무관하게
 * 같은 FilterChainProxy가 적용되므로 permitAll 검증으로 충분하다.
 */
@AutoConfigureMockMvc
class ActuatorPrometheusTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void 프로메테우스_엔드포인트가_인증_없이_히스토그램_버킷을_노출한다() throws Exception {
		// http.server.requests 지표를 최소 1건 적재 — 버킷 라인은 첫 요청 관측 후에만 나타난다
		mockMvc.perform(get("/health")).andExpect(status().isOk());

		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("http_server_requests_seconds_bucket")));
	}
}
