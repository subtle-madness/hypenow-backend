package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 에이전트 루프 검증(설계 §9) - 전송을 스크립트 fake로 갈아끼워 "툴 호출 → 되먹임 → 종료"·툴 실패
 * 되먹임·툴 호출 상한 동작을 결정론으로 고정한다. 실 LLM은 때리지 않는다.
 */
class BrandAiAgentTest {

	private final ObjectMapper om = new ObjectMapper();

	private static String functionCall(String name, String argsJson) {
		return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{"
				+ "\"name\":\"" + name + "\",\"args\":" + argsJson + "}}]}}],"
				+ "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5}}";
	}

	private static String textAnswer(String text) {
		return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + text + "\"}]}}],"
				+ "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5}}";
	}

	/** 스크립트를 순서대로 돌려주고, 다 떨어지면 마지막 응답을 반복한다. */
	private ChatTransport scripted(List<String> script, List<String> captured) {
		AtomicInteger index = new AtomicInteger();
		return body -> {
			captured.add(body);
			int i = Math.min(index.getAndIncrement(), script.size() - 1);
			return script.get(i);
		};
	}

	private BrandAiAgent agentWith(List<String> script, List<String> captured, BrandAiToolbox toolbox) {
		return new BrandAiAgent(new GeminiChatClient(scripted(script, captured), om), toolbox, om);
	}

	@Test
	void 툴을_호출하고_결과를_되먹인_뒤_텍스트로_답한다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{\"posts\":[]}", 3, List.of("ABC")));
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(
				List.of(functionCall("list_posts", "{\"brandId\":7}"), textAnswer("3건 있어요")),
				captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		assertThat(outcome.answer()).isEqualTo("3건 있어요");
		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_OK);
		assertThat(outcome.referencedShortCodes()).containsExactly("ABC");
		assertThat(outcome.toolCalls()).hasSize(1);
		assertThat(outcome.toolCalls().get(0).name()).isEqualTo("list_posts");
		assertThat(outcome.toolCalls().get(0).rows()).isEqualTo(3);
		assertThat(outcome.brandId()).isEqualTo(7L);
		assertThat(outcome.promptTokens()).isEqualTo(20);
		// 2번째 요청 본문에 functionResponse 되먹임이 실려 있어야 한다
		assertThat(captured.get(1)).contains("functionResponse").contains("list_posts");
	}

	@Test
	void 툴이_없으면_한_번의_LLM_호출로_끝난다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(List.of(textAnswer("모니터링 데이터만 답할 수 있어요")), captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "오늘 날씨?")));

		assertThat(outcome.answer()).isEqualTo("모니터링 데이터만 답할 수 있어요");
		assertThat(captured).hasSize(1);
		assertThat(outcome.toolCalls()).isEmpty();
	}

	@Test
	void 툴_실패는_첫_회만_재시도_지시로_되먹인다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(anyLong(), anyString(), any()))
				.willReturn(AiToolResult.failure("{\"error\":\"권한 없음\"}"));
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(
				List.of(functionCall("get_post", "{\"shortCode\":\"X\"}"),
						functionCall("get_post", "{\"shortCode\":\"Y\"}"),
						textAnswer("확인하지 못했어요")),
				captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		assertThat(outcome.answer()).isEqualTo("확인하지 못했어요");
		// 요청 본문은 Jackson 컴팩트 직렬화라 콜론 뒤에 공백이 없다
		assertThat(captured.get(1)).contains("\"retry\":true");
		assertThat(captured.get(2)).contains("\"retry\":false");
	}

	@Test
	void 툴_호출이_8회를_넘으면_툴_없이_답변을_강제한다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{}", 0, List.of()));
		List<String> captured = new ArrayList<>();
		// 스크립트가 끝없이 툴만 요청한다 - 상한이 없으면 무한 루프다
		BrandAiAgent agent = agentWith(List.of(functionCall("list_brands", "{}")), captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		assertThat(outcome.toolCalls()).hasSize(8);
		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_TOOL_CAP);
		// 마지막 요청은 tools 필드 없이(= 툴 호출 불가) 상한 안내를 달고 나간다
		String last = captured.get(captured.size() - 1);
		assertThat(om.readTree(last).has("tools")).isFalse();
		assertThat(last).contains("조회 가능 횟수를 모두 썼습니다");
	}
}
