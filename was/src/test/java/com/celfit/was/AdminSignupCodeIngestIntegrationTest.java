package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 가입 코드 일괄 적재(설계 2026-07-20) — /admin/signup-codes 토큰 체인 인증·검증·중복·상한 검증.
 * 싱글턴 공유 DB라 코드는 UUID로 유니크화.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "codes.api-key=test-secret-abc123")
class AdminSignupCodeIngestIntegrationTest extends IntegrationTest {

	private static final String TOKEN = "test-secret-abc123";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	private String uniqueCode(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
	}

	private org.springframework.test.web.servlet.ResultActions submit(String token, String jsonBody) throws Exception {
		var req = post("/admin/signup-codes").contentType(MediaType.APPLICATION_JSON).content(jsonBody);
		if (token != null) {
			req = req.header("Authorization", "Bearer " + token);
		}
		return mockMvc.perform(req);
	}

	@Test
	void 토큰_없으면_401() throws Exception {
		submit(null, "{\"codes\":[\"THREADS-ABCD\"]}")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").isNotEmpty());
	}

	@Test
	void 토큰_틀리면_401() throws Exception {
		submit("wrong-token", "{\"codes\":[\"THREADS-ABCD\"]}")
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 정상_적재하면_channel은_접두사에서_유도되고_inserted반환() throws Exception {
		String c1 = uniqueCode("THREADS");
		String c2 = uniqueCode("DM");
		submit(TOKEN, "{\"codes\":[\"" + c1 + "\",\"" + c2 + "\"]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.inserted").value(2))
				.andExpect(jsonPath("$.skipped").value(0));
		String channel = jdbcClient.sql("SELECT channel FROM app.signup_codes WHERE code = :c")
				.param("c", c1).query(String.class).single();
		assertThat(channel).isEqualTo("THREADS");
	}

	@Test
	void 기존코드는_스킵되고_신규만_inserted() throws Exception {
		String existing = uniqueCode("THREADS");
		jdbcClient.sql("INSERT INTO app.signup_codes (code, channel) VALUES (:c, 'THREADS')")
				.param("c", existing).update();
		String fresh = uniqueCode("THREADS");
		submit(TOKEN, "{\"codes\":[\"" + existing + "\",\"" + fresh + "\"]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.inserted").value(1))
				.andExpect(jsonPath("$.skipped").value(1));
	}

	@Test
	void 접두사없는_코드가_있으면_400이고_아무것도_저장안됨() throws Exception {
		String good = uniqueCode("THREADS");
		submit(TOKEN, "{\"codes\":[\"" + good + "\",\"NOPREFIX\"]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").isNotEmpty());
		Long cnt = jdbcClient.sql("SELECT count(*) FROM app.signup_codes WHERE code = :c")
				.param("c", good).query(Long.class).single();
		assertThat(cnt).isZero();
	}

	@Test
	void 빈_배열이면_400() throws Exception {
		submit(TOKEN, "{\"codes\":[]}").andExpect(status().isBadRequest());
	}

	@Test
	void 배치_501개면_400() throws Exception {
		StringBuilder sb = new StringBuilder("{\"codes\":[");
		for (int i = 0; i < 501; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append("\"X-").append(String.format("%04d", i)).append('\"');
		}
		sb.append("]}");
		submit(TOKEN, sb.toString()).andExpect(status().isBadRequest());
	}

	@Test
	void 배치_정확히_500개면_전부_적재() throws Exception {
		String prefix = "B" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
		StringBuilder sb = new StringBuilder("{\"codes\":[");
		for (int i = 0; i < 500; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('"').append(prefix).append('-').append(String.format("%04d", i)).append('"');
		}
		sb.append("]}");
		submit(TOKEN, sb.toString())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.inserted").value(500));
	}
}
