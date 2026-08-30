package com.celfit.was.v1.brandmonitoring.ai;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 대화 API 계약 슬라이스 검증(FE 변경요청서 §8) - 목록·상세(메시지 펼침·followUps는 마지막
 * assistant에만)·삭제·타 유저 404.
 */
@WebMvcTest(controllers = V1BrandAiConversationController.class,
		properties = {"was.cors.allowed-origins=http://localhost:3000", "monitoring.enabled=true",
				"monitoring.brand.ai.enabled=true"})
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V1BrandAiConversationControllerTest {

	@Autowired
	MockMvc mockMvc;
	@MockitoBean
	AiConversationRepository conversationRepository;
	@MockitoBean
	AiChatLogRepository logRepository;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	@Test
	void 목록은_accountIds와_메시지수를_돌려준다() throws Exception {
		given(conversationRepository.list(7L, 100L, 20)).willReturn(List.of(
				new AiConversationRepository.ConversationSummaryRow(1L, "지난주 반응 좋은 게시물",
						OffsetDateTime.parse("2026-08-30T00:00:00Z"), 4)));

		mockMvc.perform(get("/v1/brand-monitoring/ai/conversations").param("accountId", "100")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].id").value("1"))
				.andExpect(jsonPath("$.data[0].accountIds[0]").value("100"))
				.andExpect(jsonPath("$.data[0].messageCount").value(4));
	}

	@Test
	void accountId가_없으면_400이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/ai/conversations").with(user(principal())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 상세는_메시지를_펼치고_마지막_assistant에만_followUps를_싣는다() throws Exception {
		given(conversationRepository.findOwnedActive(1L, 7L)).willReturn(Optional.of(
				new AiConversationRepository.ConversationRow(1L, 100L, "지난주 반응 좋은 게시물",
						OffsetDateTime.parse("2026-08-30T00:00:00Z"))));
		var followUps1 = JsonNodeFactory.instance.arrayNode().add("이건 무시돼야 함");
		var followUps2 = JsonNodeFactory.instance.arrayNode().add("다음엔 뭘 물어볼까요?");
		given(logRepository.findByConversation(1L)).willReturn(List.of(
				new AiChatLogRepository.ConversationMessageRow("첫 질문", "첫 답변", "preset-a",
						followUps1, JsonNodeFactory.instance.arrayNode(),
						OffsetDateTime.parse("2026-08-30T00:00:00Z")),
				new AiChatLogRepository.ConversationMessageRow("둘째 질문", "둘째 답변", null,
						followUps2, JsonNodeFactory.instance.arrayNode().add("ABC"),
						OffsetDateTime.parse("2026-08-30T00:05:00Z"))));

		mockMvc.perform(get("/v1/brand-monitoring/ai/conversations/1").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value("1"))
				.andExpect(jsonPath("$.data.accountIds[0]").value("100"))
				.andExpect(jsonPath("$.data.messages.length()").value(4))
				.andExpect(jsonPath("$.data.messages[0].role").value("user"))
				.andExpect(jsonPath("$.data.messages[0].content").value("첫 질문"))
				.andExpect(jsonPath("$.data.messages[0].presetId").value("preset-a"))
				.andExpect(jsonPath("$.data.messages[0].feedback").doesNotExist())
				.andExpect(jsonPath("$.data.messages[1].role").value("assistant"))
				.andExpect(jsonPath("$.data.messages[1].followUps").doesNotExist())
				.andExpect(jsonPath("$.data.messages[2].presetId").doesNotExist())
				.andExpect(jsonPath("$.data.messages[3].role").value("assistant"))
				.andExpect(jsonPath("$.data.messages[3].followUps[0]").value("다음엔 뭘 물어볼까요?"))
				.andExpect(jsonPath("$.data.messages[3].references[0]").value("ABC"));
	}

	@Test
	void 답변없는_행은_user_메시지만_남긴다() throws Exception {
		given(conversationRepository.findOwnedActive(1L, 7L)).willReturn(Optional.of(
				new AiConversationRepository.ConversationRow(1L, 100L, "질문만 있는 대화",
						OffsetDateTime.parse("2026-08-30T00:00:00Z"))));
		given(logRepository.findByConversation(1L)).willReturn(List.of(
				new AiChatLogRepository.ConversationMessageRow("답변 없는 질문", null, null,
						JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.arrayNode(),
						OffsetDateTime.parse("2026-08-30T00:00:00Z"))));

		mockMvc.perform(get("/v1/brand-monitoring/ai/conversations/1").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.messages.length()").value(1))
				.andExpect(jsonPath("$.data.messages[0].role").value("user"));
	}

	@Test
	void 남의_대화는_상세_조회에서_404다() throws Exception {
		given(conversationRepository.findOwnedActive(anyLong(), anyLong())).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/brand-monitoring/ai/conversations/1").with(user(principal())))
				.andExpect(status().isNotFound());
	}

	@Test
	void 삭제는_204다() throws Exception {
		given(conversationRepository.softDelete(1L, 7L)).willReturn(1);

		mockMvc.perform(delete("/v1/brand-monitoring/ai/conversations/1").with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());
	}

	@Test
	void 남의_대화_또는_이미_삭제된_대화_삭제는_404다() throws Exception {
		given(conversationRepository.softDelete(anyLong(), anyLong())).willReturn(0);

		mockMvc.perform(delete("/v1/brand-monitoring/ai/conversations/1").with(user(principal())).with(csrf()))
				.andExpect(status().isNotFound());
	}

	@Test
	void limit은_최대_50으로_보정된다() throws Exception {
		given(conversationRepository.list(anyLong(), anyLong(), anyInt())).willReturn(List.of());

		mockMvc.perform(get("/v1/brand-monitoring/ai/conversations").param("accountId", "100")
						.param("limit", "999").with(user(principal())))
				.andExpect(status().isOk());

		org.mockito.BDDMockito.then(conversationRepository).should().list(7L, 100L, 50);
	}
}
