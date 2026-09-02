package com.celfit.was.v1.brandmonitoring.ai;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 피드백 저장 API 계약 슬라이스 검증(2026-09-02) - PUT 성공·덮어쓰기·검증 400 3종·타인 메시지 404,
 * DELETE 204·404.
 */
@WebMvcTest(controllers = V1BrandAiFeedbackController.class,
		properties = {"was.cors.allowed-origins=http://localhost:3000", "monitoring.enabled=true",
				"monitoring.brand.ai.enabled=true"})
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V1BrandAiFeedbackControllerTest {

	@Autowired
	MockMvc mockMvc;
	@MockitoBean
	AiChatFeedbackRepository feedbackRepository;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	@Test
	void PUT은_저장된_피드백을_돌려준다() throws Exception {
		given(feedbackRepository.upsert(456L, 7L, "down", "설명이 부족해요")).willReturn(Optional.of(
				new AiChatFeedbackRepository.FeedbackRow("down", "설명이 부족해요",
						OffsetDateTime.parse("2026-09-02T00:00:00Z"))));

		mockMvc.perform(put("/v1/brand-monitoring/ai/messages/456/feedback").with(user(principal())).with(csrf())
						.contentType("application/json")
						.content("{\"value\":\"down\",\"comment\":\"설명이 부족해요\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.messageId").value("456"))
				.andExpect(jsonPath("$.data.feedback.value").value("down"))
				.andExpect(jsonPath("$.data.feedback.comment").value("설명이 부족해요"));
	}

	@Test
	void PUT은_같은_메시지에_다시_보내면_덮어쓴다() throws Exception {
		given(feedbackRepository.upsert(456L, 7L, "up", null)).willReturn(Optional.of(
				new AiChatFeedbackRepository.FeedbackRow("up", null,
						OffsetDateTime.parse("2026-09-02T00:10:00Z"))));

		mockMvc.perform(put("/v1/brand-monitoring/ai/messages/456/feedback").with(user(principal())).with(csrf())
						.contentType("application/json")
						.content("{\"value\":\"up\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.feedback.value").value("up"))
				.andExpect(jsonPath("$.data.feedback.comment").doesNotExist());

		org.mockito.BDDMockito.then(feedbackRepository).should().upsert(456L, 7L, "up", null);
	}

	@Test
	void value가_up_down이_아니면_400이다() throws Exception {
		mockMvc.perform(put("/v1/brand-monitoring/ai/messages/456/feedback").with(user(principal())).with(csrf())
						.contentType("application/json")
						.content("{\"value\":\"maybe\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void comment가_500자를_넘으면_400이다() throws Exception {
		String longComment = "가".repeat(501);

		mockMvc.perform(put("/v1/brand-monitoring/ai/messages/456/feedback").with(user(principal())).with(csrf())
						.contentType("application/json")
						.content("{\"value\":\"up\",\"comment\":\"" + longComment + "\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void messageId가_숫자가_아니면_400이다() throws Exception {
		mockMvc.perform(put("/v1/brand-monitoring/ai/messages/abc/feedback").with(user(principal())).with(csrf())
						.contentType("application/json")
						.content("{\"value\":\"up\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 남의_메시지_또는_존재하지_않는_메시지는_404다() throws Exception {
		given(feedbackRepository.upsert(anyLong(), anyLong(), anyString(), org.mockito.ArgumentMatchers.any()))
				.willReturn(Optional.empty());

		mockMvc.perform(put("/v1/brand-monitoring/ai/messages/999/feedback").with(user(principal())).with(csrf())
						.contentType("application/json")
						.content("{\"value\":\"up\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void DELETE는_204다() throws Exception {
		given(feedbackRepository.clear(456L, 7L)).willReturn(1);

		mockMvc.perform(delete("/v1/brand-monitoring/ai/messages/456/feedback").with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());
	}

	@Test
	void DELETE는_남의_메시지면_404다() throws Exception {
		given(feedbackRepository.clear(anyLong(), anyLong())).willReturn(0);

		mockMvc.perform(delete("/v1/brand-monitoring/ai/messages/456/feedback").with(user(principal())).with(csrf()))
				.andExpect(status().isNotFound());
	}
}
