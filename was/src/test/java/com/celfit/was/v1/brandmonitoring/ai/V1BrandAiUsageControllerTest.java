package com.celfit.was.v1.brandmonitoring.ai;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 사용량 조회 API 계약 슬라이스 검증(FE 변경요청서 §9.2). */
@WebMvcTest(controllers = V1BrandAiUsageController.class,
		properties = {"was.cors.allowed-origins=http://localhost:3000", "monitoring.enabled=true",
				"monitoring.brand.ai.enabled=true"})
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V1BrandAiUsageControllerTest {

	@Autowired
	MockMvc mockMvc;
	@MockitoBean
	AiChatQuota quota;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	@Test
	void 상한_잔여_리셋시각을_돌려준다() throws Exception {
		given(quota.usage(7L)).willReturn(new AiChatQuota.Usage(30, 12,
				OffsetDateTime.parse("2026-08-31T00:00:00+09:00")));

		mockMvc.perform(get("/v1/brand-monitoring/ai/usage").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.dailyLimit").value(30))
				.andExpect(jsonPath("$.data.remaining").value(12))
				.andExpect(jsonPath("$.data.resetAt").value("2026-08-31T00:00:00+09:00"));
	}

	@Test
	void 미인증이면_401이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/ai/usage"))
				.andExpect(status().isUnauthorized());
	}
}
