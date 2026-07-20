package com.celfit.was;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** CODES_API_KEY 미설정이면 /admin/signup-codes가 503으로 fail-closed(설계 2026-07-20). */
@AutoConfigureMockMvc
@TestPropertySource(properties = "codes.api-key=")
class AdminSignupCodeIngestFailClosedTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void 키_미설정이면_503() throws Exception {
		mockMvc.perform(post("/admin/signup-codes")
						.header("Authorization", "Bearer anything")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"codes\":[\"THREADS-ABCD\"]}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error").isNotEmpty());
	}
}
