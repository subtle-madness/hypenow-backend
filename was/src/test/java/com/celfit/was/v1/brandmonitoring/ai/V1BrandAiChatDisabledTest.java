package com.celfit.was.v1.brandmonitoring.ai;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 킬 스위치 검증(설계 §7) - monitoring.brand.ai.enabled=false면 컨트롤러 빈이 등록되지 않아
 * 표면 자체가 없다(404). AD_DISCLOSURE_EXPOSE와 달리 "중립값"이 아니라 "표면 부재"인 이유:
 * 어시스턴트는 값 하나가 아니라 기능 전체라 끄면 사라지는 게 맞다.
 */
@WebMvcTest(controllers = V1BrandAiChatController.class,
		properties = {"was.cors.allowed-origins=http://localhost:3000", "monitoring.enabled=true",
				"monitoring.brand.ai.enabled=false"})
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V1BrandAiChatDisabledTest {

	@Autowired
	MockMvc mockMvc;
	@MockitoBean
	BrandAiAgent agent;
	@MockitoBean
	AiChatQuota quota;
	@MockitoBean
	AiChatLogRepository logRepository;

	@Test
	void 킬_스위치가_꺼져_있으면_표면이_없다() throws Exception {
		AppUserDetails principal = new AppUserDetails(new AppUser(7L, "user@example.com", "hash",
				"USER", OffsetDateTime.parse("2026-06-01T00:00:00Z")));

		mockMvc.perform(post("/v1/brand-monitoring/ai/chat").with(user(principal)).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"messages\":[{\"role\":\"user\",\"content\":\"안녕\"}]}"))
				.andExpect(status().isNotFound());
	}
}
