package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
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

	/** 한 턴에 함수 호출 여러 개를 함께 돌려주는 응답(한계 재도출 2026-08-31) - MAX_TOOL_CALLS(24)가
	 * MAX_LLM_CALLS(12)보다 커진 뒤로는 턴당 1개씩으로 툴 상한에 닿는 시나리오를 LLM 호출 상한 안에서
	 * 재현할 수 없다 - 실제 모델의 병렬 호출(스펙 §6 유도)과 같은 모양으로 턴당 여러 개를 묶는다. */
	private static String multiFunctionCall(String name, String argsJson, int count) {
		StringBuilder parts = new StringBuilder();
		for (int i = 0; i < count; i++) {
			if (i > 0) {
				parts.append(',');
			}
			parts.append("{\"functionCall\":{\"name\":\"").append(name).append("\",\"args\":").append(argsJson)
					.append("}}");
		}
		return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[" + parts + "]}}],"
				+ "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5}}";
	}

	/** 프롬프트 토큰 예산(N5) 소진을 재현하는 함수 호출 응답 - usageMetadata.promptTokenCount를 직접 지정한다. */
	private static String functionCallWithPromptTokens(String name, String argsJson, int promptTokenCount) {
		return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{"
				+ "\"name\":\"" + name + "\",\"args\":" + argsJson + "}}]}}],"
				+ "\"usageMetadata\":{\"promptTokenCount\":" + promptTokenCount + ",\"candidatesTokenCount\":5}}";
	}

	/** 안전 필터·길이 제한으로 후보 파트가 비어 끝난 응답(I7) - finishReason만 있고 text·functionCall이 없다. */
	private static String blockedResponse(String finishReason) {
		return "{\"candidates\":[{\"finishReason\":\"" + finishReason + "\","
				+ "\"content\":{\"role\":\"model\",\"parts\":[]}}],"
				+ "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":0}}";
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
		return agentWith(script, captured, toolbox, Clock.systemUTC());
	}

	private BrandAiAgent agentWith(List<String> script, List<String> captured, BrandAiToolbox toolbox, Clock clock) {
		return new BrandAiAgent(new GeminiChatClient(scripted(script, captured), om), toolbox, om, clock);
	}

	/** 첫 확인 이후 항상 예산을 넘긴 시각을 돌려주는 시계 - "벽시계 예산이 이미 소진된 상태"를
	 * 결정론으로 재현한다(run()이 시작할 때 deadline을 한 번 계산하고, 그다음 매 호출 전 검사에서
	 * 예산 초과로 나오게 한다). */
	private static Clock exhaustedBudgetClock() {
		Instant start = Instant.parse("2026-08-27T00:00:00Z");
		AtomicInteger calls = new AtomicInteger();
		return new Clock() {
			@Override
			public ZoneOffset getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				// 1번째 호출(run 진입 시 deadline 계산)만 start, 그 뒤로는 예산(TIME_BUDGET_MILLIS)을 넘긴 시각.
				return calls.getAndIncrement() == 0 ? start : start.plusMillis(BrandAiAgent.TIME_BUDGET_MILLIS + 1);
			}
		};
	}

	@Test
	void 툴을_호출하고_결과를_되먹인_뒤_텍스트로_답한다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{\"posts\":[]}", 3, List.of("ABC")));
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(
				List.of(functionCall("list_posts", "{\"brandId\":7}"), textAnswer("ABC 게시물 등 3건 있어요")),
				captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		assertThat(outcome.answer()).isEqualTo("ABC 게시물 등 3건 있어요");
		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_OK);
		assertThat(outcome.limitReached()).isNull();
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
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
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

	/** 날조 방지 서버 가드(2026-09-02) - 툴 호출 0회 + 표가 있는 첫 답변은 1회 재시도로 되먹이고,
	 * 재시도 턴에서 모델이 실제로 툴을 호출한 뒤 그 결과로 답하면 caveat 없이 그대로 통과시킨다. */
	@Test
	void 날조_의심_답변은_1회_재시도해_툴_호출_후_그라운딩되면_caveat_없이_답한다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{\"posts\":[]}", 3, List.of("ABC")));
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(
				List.of(
						textAnswer("| 계정 | 게시물 |\\n| @yoon_yoon_ | 11 |"),
						functionCall("list_posts", "{\"brandId\":7}"),
						textAnswer("확인해보니 3건 있어요")),
				captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "시딩 우선순위 기준 잡아줘")));

		assertThat(outcome.answer()).isEqualTo("확인해보니 3건 있어요");
		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_OK);
		assertThat(outcome.toolCalls()).hasSize(1);
		assertThat(outcome.toolCalls().get(0).name()).isEqualTo("list_posts");
		// 재시도 지시(UNGROUNDED_RETRY_NOTE)가 다음 요청 본문에 user 턴으로 실렸는지 확인한다.
		assertThat(captured.get(1)).contains("검증").contains("조회 없이 답할 수 있는 내용만");
	}

	/** 재시도 턴에서도 모델이 여전히 툴 없이 표·계정명을 쓰면, 정지 조건(재시도 1회 한도)에 걸려 답변
	 * 맨 앞에 서버 강제 caveat를 붙여 반환한다. */
	@Test
	void 재시도_후에도_날조_신호가_남으면_답변_앞에_서버_caveat를_붙인다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(
				List.of(
						textAnswer("| 계정 | 게시물 |\\n| @yoon_yoon_ | 11 |"),
						textAnswer("| 계정 | 게시물 |\\n| @yoon_yoon_ | 11 |")),
				captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "시딩 우선순위 기준 잡아줘")));

		assertThat(outcome.answer()).startsWith("(자동 검증)");
		assertThat(outcome.answer()).contains("@yoon_yoon_");
		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_OK);
		assertThat(outcome.toolCalls()).isEmpty();
		// 재시도 1회만 쓰고 끝났다(첫 호출 + 재시도 1회 = 2).
		assertThat(captured).hasSize(2);
	}

	@Test
	void 툴_상한_도달_강제_답변이면_limitReached가_budget이다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{}", 0, List.of()));
		List<String> captured = new ArrayList<>();
		// 한 턴에 3개씩 묶어 MAX_TOOL_CALLS(24)에 정확히 도달시키고, 그다음(강제 답변 턴)엔 실제
		// Vertex가 mode=NONE을 지키듯 텍스트로 답한다. MAX_TOOL_CALLS(24)가 MAX_LLM_CALLS(12)보다
		// 커진 뒤로는(한계 재도출 2026-08-31 - 툴 상한이 안전망으로 강등) 턴당 1개씩으로는 LLM 호출
		// 상한 안에서 툴 상한에 닿을 수 없다 - 병렬 호출(스펙 §6 유도)과 같은 모양으로 재현한다.
		int toolsPerTurn = 3;
		int toolTurns = BrandAiAgent.MAX_TOOL_CALLS / toolsPerTurn;
		List<String> script = new ArrayList<>();
		for (int i = 0; i < toolTurns; i++) {
			script.add(multiFunctionCall("list_brands", "{}", toolsPerTurn));
		}
		script.add(textAnswer("확인한 것만 답할게요"));
		BrandAiAgent agent = agentWith(script, captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		assertThat(outcome.toolCalls()).hasSize(BrandAiAgent.MAX_TOOL_CALLS);
		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_TOOL_CAP);
		assertThat(outcome.limitReached()).isEqualTo(BrandAiAgent.LIMIT_BUDGET);
		assertThat(outcome.answer()).isEqualTo("확인한 것만 답할게요");
		// 강제 답변 턴 요청은 tools 선언은 그대로 싣되 toolConfig mode=NONE으로 호출만 막는다(I8) -
		// tools를 통째로 빼면 이전 턴의 functionCall/functionResponse 파트가 남은 히스토리와 조합돼
		// Vertex 400 위험이 있다.
		String last = captured.get(captured.size() - 1);
		JsonNode lastBody = om.readTree(last);
		assertThat(lastBody.path("tools").path(0).path("functionDeclarations").size()).isGreaterThan(0);
		assertThat(lastBody.path("toolConfig").path("functionCallingConfig").path("mode").asString())
				.isEqualTo("NONE");
	}

	@Test
	void 강제_답변_턴에서도_툴만_요청하면_1회_만에_루프를_끊는다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{}", 0, List.of()));
		List<String> captured = new ArrayList<>();
		// 강제 답변 턴에서도(mode=NONE) 계속 툴만 요청하는 병리적 경우(C2 잔여, 2026-08-28 재리뷰) -
		// 강제 답변 턴을 1회로 제한하므로 툴 상한(MAX_TOOL_CALLS) 도달 직후 1번 더 부르고는 더 이상
		// LLM을 부르지 않고 그 자리에서 끊는다 - MAX_LLM_CALLS(12)까지 도지 않는다. 한 턴에 3개씩
		// 묶는 이유는 위 상한 도달 테스트와 같다(MAX_TOOL_CALLS(24) > MAX_LLM_CALLS(12)). scripted()는
		// 목록이 1개뿐이면 매 턴 같은 응답을 반복하므로, 강제 답변 턴에도 같은 3-호출 응답이 나가
		// 병리를 그대로 재현한다.
		int toolsPerTurn = 3;
		int toolTurns = BrandAiAgent.MAX_TOOL_CALLS / toolsPerTurn;
		BrandAiAgent agent = agentWith(List.of(multiFunctionCall("list_brands", "{}", toolsPerTurn)), captured,
				toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_LLM_CALL_CAP);
		assertThat(outcome.answer()).contains("정리하지 못했어요");
		// 이 시나리오의 원인은 툴 상한 도달이라 budget이다(한계 재도출 2026-08-31, 스펙 §5).
		assertThat(outcome.limitReached()).isEqualTo(BrandAiAgent.LIMIT_BUDGET);
		// 툴 상한까지 정상 호출 toolTurns번 + 강제 답변 턴 1번에서 멈춘다(추가 LLM 호출 없음).
		assertThat(captured).hasSize(toolTurns + 1);
	}

	@Test
	void 벽시계_예산_소진_강제_답변이면_limitReached가_time이다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		List<String> captured = new ArrayList<>();
		// 첫 LLM 호출 전에 이미 예산이 다 떨어진 상태 - 스크립트가 툴 호출을 내려도 강제 답변 턴으로
		// 바로 전환돼야 하고, 그 턴이 텍스트를 냈으니 그 텍스트로 끝나야 한다(FALLBACK_ANSWER가 아님).
		BrandAiAgent agent = agentWith(List.of(textAnswer("시간이 없어 지금까지 확인한 것만 답할게요")),
				captured, toolbox, exhaustedBudgetClock());

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		assertThat(outcome.answer()).isEqualTo("시간이 없어 지금까지 확인한 것만 답할게요");
		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_TOOL_CAP);
		assertThat(outcome.limitReached()).isEqualTo(BrandAiAgent.LIMIT_TIME);
		assertThat(captured).hasSize(1);
		assertThat(captured.get(0)).contains("답변 시간이 얼마 남지 않았습니다");
		JsonNode body = om.readTree(captured.get(0));
		assertThat(body.path("toolConfig").path("functionCallingConfig").path("mode").asString())
				.isEqualTo("NONE");
	}

	@Test
	void 프롬프트_토큰_예산을_넘으면_강제_답변으로_전환하고_TIME_BUDGET_NOTE를_붙인다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{}", 0, List.of()));
		List<String> captured = new ArrayList<>();
		// 1번째 응답만으로 누적 promptTokens가 예산(PROMPT_TOKEN_BUDGET)을 채운다 - 툴 상한은 전혀
		// 걸리지 않았으니(1회) 원인은 토큰 예산이지 툴 횟수가 아니다 - N3 분기상 TIME_BUDGET_NOTE가
		// 나가야 한다. limitReached는 그래도 budget이다(토큰 예산도 budget - 한계 재도출 스펙 §5).
		BrandAiAgent agent = agentWith(
				List.of(functionCallWithPromptTokens("list_brands", "{}", BrandAiAgent.PROMPT_TOKEN_BUDGET),
						textAnswer("토큰을 많이 써서 이제 답할게요")),
				captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		assertThat(outcome.answer()).isEqualTo("토큰을 많이 써서 이제 답할게요");
		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_TOOL_CAP);
		assertThat(outcome.limitReached()).isEqualTo(BrandAiAgent.LIMIT_BUDGET);
		assertThat(captured).hasSize(2);
		assertThat(captured.get(1)).contains("답변 시간이 얼마 남지 않았습니다");
	}

	@Test
	void 실패한_툴_결과의_brandId는_따지_않는다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willReturn(AiToolResult.failure("{\"error\":\"권한 없음\"}"));
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(
				List.of(functionCall("list_posts", "{\"brandId\":99}"), textAnswer("확인하지 못했어요")),
				captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		// 소유 검증 실패 등 failed 결과의 brandId는 신뢰할 수 없다(M1) - 로그에 남기면 안 된다
		assertThat(outcome.brandId()).isNull();
	}

	// ---------- 프리셋 verified 플랜 선실행 주입(스펙 §6) ----------

	/**
	 * 플랜 선실행 주입 - 프리셋에 verified 플랜이 있으면 에이전트 루프(첫 LLM 호출)에 들어가기 전에
	 * 그 플랜을 먼저 실행해 functionCall/functionResponse 쌍으로 대화에 심어 넣는다. 그 결과 모델은
	 * 첫 호출부터 이미 채워진 데이터를 보게 되므로, 스크립트가 텍스트 답변만 줘도(추가 툴 호출 없이)
	 * 바로 끝나야 한다.
	 */
	@Test
	void 프리셋_플랜이_있으면_첫_LLM_호출_전에_선실행_결과가_대화에_주입된다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(),
				org.mockito.ArgumentMatchers.eq("aggregate_posts"), any()))
				.willReturn(AiToolResult.ok("{\"groups\":[]}", 5, List.of("ABC")));
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(List.of(textAnswer("상위 인플루언서 10명이에요")), captured, toolbox);
		List<BrandAiPresets.PlannedCall> plan = List.of(new BrandAiPresets.PlannedCall("aggregate_posts",
				"{\"groupBy\":\"author\",\"orderBy\":\"reachMultiple\",\"limit\":10,\"minSample\":2}"));

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "효율 좋은 인플루언서")), 7L,
				null, "", plan);

		var argsCaptor = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
		then(toolbox).should(times(1)).execute(any(BrandAiToolbox.ToolSession.class),
				org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("aggregate_posts"),
				argsCaptor.capture());
		// brandId는 플랜에 하드코딩돼 있지 않다 - 실행 시 세션 brandId(7L)로 채워진다.
		assertThat(argsCaptor.getValue().path("groupBy").asString()).isEqualTo("author");
		assertThat(argsCaptor.getValue().path("brandId").asLong()).isEqualTo(7L);
		// 첫 LLM 요청 본문에 이미 선실행 functionCall/functionResponse 쌍이 실려 있어야 한다.
		assertThat(captured.get(0)).contains("functionCall").contains("aggregate_posts").contains("functionResponse");
		// 선실행 functionCall은 모델이 생성한 게 아니라 우리가 합성했으니 서명이 없다 - Gemini 3.x
		// 강제 검증(공식 문서 "Thought signatures")을 피하려면 공식 더미 서명이 실려 있어야 한다.
		assertThat(captured.get(0)).contains("\"thoughtSignature\":\"context_engineering_is_the_way_to_go\"");
		// 선실행분도 tool_calls 로그에 포함된다(관측 일관성, 스펙 §6).
		assertThat(outcome.toolCalls()).hasSize(1);
		assertThat(outcome.toolCalls().get(0).name()).isEqualTo("aggregate_posts");
		assertThat(outcome.answer()).isEqualTo("상위 인플루언서 10명이에요");
		// 선실행 결과만으로 답이 나와 모델은 딱 1번만 불렸다(추가 조회 없음).
		assertThat(captured).hasSize(1);
	}

	/**
	 * 플랜 실행 실패 폴백 - 선실행이 실패하면(예: 소유 검증 실패) 대화에 아무 것도 주입하지 않고 기존
	 * 자유 경로 그대로 진행한다. 실패한 선실행은 tool_calls 로그에도 남지 않는다.
	 */
	@Test
	void 플랜_실행이_실패하면_주입_없이_기존_경로로_폴백한다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willReturn(AiToolResult.failure("{\"error\":\"권한 없음\"}"));
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(List.of(textAnswer("확인하지 못했어요")), captured, toolbox);
		List<BrandAiPresets.PlannedCall> plan = List.of(
				new BrandAiPresets.PlannedCall("aggregate_posts", "{\"groupBy\":\"sponsorship\"}"));

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "협찬 비교")), 7L, null, "",
				plan);

		// 선실행이 실패했으니 첫 요청 본문에 functionResponse가 실리지 않는다(주입 없음).
		assertThat(captured.get(0)).doesNotContain("functionResponse");
		// 실패한 선실행은 로그에도 남지 않는다 - 성공한 것만 기록한다.
		assertThat(outcome.toolCalls()).isEmpty();
		assertThat(outcome.answer()).isEqualTo("확인하지 못했어요");
	}

	/**
	 * 참조 shortCode 필터(N7, 2026-08-28) - 툴이 이번 실행에서 건드린 코드 전부가 아니라 답변 텍스트에
	 * 실제로 등장하는 코드만 referencedShortCodes에 남아야 한다. 3건(ABC·DEF·GHI) 중 답변은 ABC만
	 * 언급하므로 나머지 둘은 빠져야 한다.
	 */
	@Test
	void referencedShortCodes는_답변에_실제_등장한_코드만_남긴다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{\"posts\":[]}", 3, List.of("ABC", "DEF", "GHI")));
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(
				List.of(functionCall("search_posts", "{\"brandId\":7,\"query\":\"세럼\"}"),
						textAnswer("ABC 게시물에서만 언급을 확인했어요")),
				captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "몇 번 언급됐어?")));

		assertThat(outcome.referencedShortCodes()).containsExactly("ABC");
	}

	/** 답변에 shortCode가 하나도 등장하지 않으면 참조 목록은 빈 배열이어야 한다(설계 §요구). */
	@Test
	void 답변에_인용된_코드가_없으면_referencedShortCodes는_빈_배열이다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{\"totalMatches\":85}", 85, List.of("ABC", "DEF")));
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(
				List.of(functionCall("search_posts", "{\"brandId\":7,\"query\":\"세럼\"}"),
						textAnswer("최근 30일 동안 총 85건 언급됐어요")),
				captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "몇 번 언급됐어?")));

		assertThat(outcome.referencedShortCodes()).isEmpty();
	}

	@Test
	void 안전필터나_길이제한으로_막히면_OUTCOME_BLOCKED로_기록한다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(List.of(blockedResponse("MAX_TOKENS")), captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_BLOCKED);
		assertThat(outcome.answer()).contains("답변을 만들지 못했어요");
		assertThat(outcome.answered()).isFalse();
		assertThat(outcome.limitReached()).isNull();
	}

	// ---------- FE 변경요청서 2026-08-28 T3(scope 배선)·T7(references) ----------

	/** 기존 2-인자 run()은 scope 없음(무필터)과 동일해야 한다 - 위 테스트 전체가 이 보장 위에 서 있다. */
	@Test
	void 기존_2인자_run은_answered_true와_함께_동작한다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(List.of(textAnswer("답변입니다")), captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		assertThat(outcome.answered()).isTrue();
	}

	/**
	 * 브랜드 컨텍스트 선주입(2026-08-31 툴·한계 재설계, 스펙 §6) - 세션 brandId가 있으면
	 * {@link BrandAiToolbox#brandContextLine}이 시스템 프롬프트에 미리 실려야 한다. list_brands 첫
	 * 왕복 없이 바로 답할 수 있는지는 이 문구가 캡처된 systemInstruction에 있는지로 검증한다.
	 */
	@Test
	void 세션_brandId가_있으면_시스템_프롬프트에_브랜드_컨텍스트가_선주입된다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.brandContextLine(1L, 7L)).willReturn("\n\n[브랜드 컨텍스트] 이 대화의 브랜드: brandId=7");
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(List.of(textAnswer("답변")), captured, toolbox);

		agent.run(1L, List.of(new AiChatMessage("user", "알려줘")), 7L, null, "", List.of());

		JsonNode body = om.readTree(captured.get(0));
		assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asString())
				.contains("[브랜드 컨텍스트] 이 대화의 브랜드: brandId=7");
	}

	/**
	 * 용어 사전 상시 주입(2026-09-01 구조 개선, 스펙 §5) - {@link BrandAiGlossary#SECTION}이 세션
	 * brandId 유무와 무관하게 시스템 프롬프트에 항상 실려야 한다(프리셋과 무관 전 질문 적용).
	 */
	@Test
	void 시스템_프롬프트에_용어_정의_섹션이_상시_주입된다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.brandContextLine(1L, 7L)).willReturn("\n\n[브랜드 컨텍스트] 이 대화의 브랜드: brandId=7");
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(List.of(textAnswer("답변1"), textAnswer("답변2")), captured, toolbox);

		agent.run(1L, List.of(new AiChatMessage("user", "알려줘")), 7L, null, "", List.of());
		agent.run(1L, List.of(new AiChatMessage("user", "알려줘")), null, null, "", List.of());

		assertThat(captured).hasSize(2);
		for (String body : captured) {
			String systemText = om.readTree(body).path("systemInstruction").path("parts").path(0).path("text")
					.asString();
			assertThat(systemText).contains("[용어 정의]").contains("sponsorship");
		}
	}

	/** extraSystemPrompt(scope 요약·프리셋 지시문, T3·T4)는 시스템 프롬프트 뒤에 그대로 이어붙는다. */
	@Test
	void extraSystemPrompt는_시스템_프롬프트_뒤에_붙는다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(List.of(textAnswer("답변")), captured, toolbox);

		agent.run(1L, List.of(new AiChatMessage("user", "알려줘")), null, "\n\n[프리셋] 힌트 문구");

		JsonNode body = om.readTree(captured.get(0));
		assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asString())
				.contains("[프리셋] 힌트 문구");
	}

	/** scope는 툴 실행 세션에 실려 {@link BrandAiToolbox#execute}로 그대로 전달돼야 한다(T3). */
	@Test
	void scope는_툴_세션에_실려_전달된다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{}", 0, List.of()));
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(
				List.of(functionCall("list_brands", "{}"), textAnswer("답변")), captured, toolbox);
		AiScope scope = new AiScope(null, null, "reels", null, null, null, null, null);

		agent.run(1L, List.of(new AiChatMessage("user", "알려줘")), scope, "");

		var sessionCaptor = org.mockito.ArgumentCaptor.forClass(BrandAiToolbox.ToolSession.class);
		org.mockito.Mockito.verify(toolbox).execute(sessionCaptor.capture(), anyLong(), anyString(), any());
		assertThat(sessionCaptor.getValue().scope()).isEqualTo(scope);
	}

	/**
	 * references(FE §7) - 툴박스가 mock이라 {@link BrandAiToolbox.ToolSession}의 인덱스 캐시는 비어
	 * 있다(real indexFor를 타지 않음). 이 경우 라벨은 최소 폴백("게시물 {shortCode}")이어야 한다 -
	 * 캐시가 채워진 실제 라벨 조립은 {@code BrandAiToolboxIntegrationTest}가 검증한다.
	 */
	@Test
	void references는_캐시에_없는_shortCode에_최소_폴백_라벨을_쓴다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{\"posts\":[]}", 3, List.of("ABC")));
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(
				List.of(functionCall("list_posts", "{\"brandId\":7}"), textAnswer("ABC 게시물이에요")),
				captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "알려줘")));

		assertThat(outcome.references()).hasSize(1);
		assertThat(outcome.references().get(0).shortCode()).isEqualTo("ABC");
		assertThat(outcome.references().get(0).label()).isEqualTo("게시물 ABC");
	}

	/** 답변에 언급 안 된 코드는 referencedShortCodes와 마찬가지로 references에도 남지 않는다. */
	@Test
	void references는_답변에_인용되지_않은_코드는_담지_않는다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{\"totalMatches\":85}", 85, List.of("ABC", "DEF")));
		List<String> captured = new ArrayList<>();
		BrandAiAgent agent = agentWith(
				List.of(functionCall("search_posts", "{\"brandId\":7,\"query\":\"세럼\"}"),
						textAnswer("최근 30일 동안 총 85건 언급됐어요")),
				captured, toolbox);

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "몇 번 언급됐어?")));

		assertThat(outcome.references()).isEmpty();
	}

	// --- 스트리밍 오버로드(T3, FE 변경요청서 §3.2) ---

	private static String textChunk(String text) {
		return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + text + "\"}]}}]}";
	}

	private static String functionCallChunk(String name, String argsJson) {
		return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{"
				+ "\"name\":\"" + name + "\",\"args\":" + argsJson + "}}]}}],"
				+ "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5}}";
	}

	/** LLM 호출(턴)마다 청크 목록을 하나씩 순서대로 흘려주는 fake - postStream만 오버라이드한다. */
	private BrandAiAgent streamingAgentWith(List<List<String>> chunkScriptPerTurn, BrandAiToolbox toolbox,
			Clock clock) {
		AtomicInteger turnIndex = new AtomicInteger();
		ChatTransport transport = new ChatTransport() {
			@Override
			public String post(String jsonBody) {
				throw new UnsupportedOperationException("스트리밍 테스트는 post()를 쓰지 않는다");
			}

			@Override
			public void postStream(String jsonBody, Consumer<String> onData) {
				int i = Math.min(turnIndex.getAndIncrement(), chunkScriptPerTurn.size() - 1);
				chunkScriptPerTurn.get(i).forEach(onData::accept);
			}
		};
		return new BrandAiAgent(new GeminiChatClient(transport, om), toolbox, om, clock);
	}

	private static BrandAiAgent.StreamListener listener(List<String> deltas, List<String> toolCalls) {
		return new BrandAiAgent.StreamListener() {
			@Override
			public void onAnswerDelta(String textDelta) {
				deltas.add(textDelta);
			}

			@Override
			public void onToolCall(String toolName, int index) {
				toolCalls.add(toolName + "#" + index);
			}
		};
	}

	/**
	 * 홀드백 불변식(T3 클래스 상단 주석) 검증 - 툴이 선언된 일반 턴은 텍스트 델타를 청크마다 따로
	 * 방출하지 않고, 그 턴이 순수 텍스트로 끝난(=최종 답변 확정) 시점에 누적 텍스트를 한 번에
	 * 방출한다. 첫 턴(functionCall)에서는 아예 방출이 없어야 한다.
	 */
	@Test
	void 홀드백_일반_턴은_텍스트가_순수_텍스트로_끝난_시점에만_한번에_방출한다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{\"posts\":[]}", 3, List.of("ABC")));
		List<List<String>> chunkScript = List.of(
				List.of(functionCallChunk("list_posts", "{\"brandId\":7}")),
				List.of(textChunk("앞"), textChunk("뒤")));
		BrandAiAgent agent = streamingAgentWith(chunkScript, toolbox, Clock.systemUTC());
		List<String> deltas = new ArrayList<>();
		List<String> toolCalls = new ArrayList<>();

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "질문")), null, null, "",
				List.of(), listener(deltas, toolCalls), () -> false);

		// 청크별로 "앞"·"뒤"가 따로 나가지 않고, 턴 완성 후 합쳐진 텍스트 1건만 나간다.
		assertThat(deltas).containsExactly("앞뒤");
		assertThat(outcome.answer()).isEqualTo("앞뒤");
		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_OK);
		assertThat(toolCalls).containsExactly("list_posts#1");
	}

	/** 진행 상태 통지(2026-09-02) 검증용 - onThinking·onToolCall·onWriting·onAnswerDelta를 호출 순서
	 * 그대로 하나의 로그에 쌓는다. */
	private static BrandAiAgent.StreamListener orderedListener(List<String> events) {
		return new BrandAiAgent.StreamListener() {
			@Override
			public void onAnswerDelta(String textDelta) {
				events.add("delta:" + textDelta);
			}

			@Override
			public void onToolCall(String toolName, int index) {
				events.add("tool:" + toolName + "#" + index);
			}

			@Override
			public void onThinking(int llmCallIndex) {
				events.add("thinking:" + llmCallIndex);
			}

			@Override
			public void onWriting() {
				events.add("writing");
			}
		};
	}

	/**
	 * 진행 상태 확장(2026-09-02) 검증 - "툴 1회 후 텍스트로 종료" 시나리오에서 thinking이 LLM 호출마다
	 * (재시도 없이도 매 턴) 먼저 나가고, 홀드백 중인 마지막 턴은 텍스트 청크가 처음 도착한 시점에
	 * writing이 1회만 나간 뒤 델타가 홀드백 규칙대로 턴 종료 시 일괄 방출된다.
	 */
	@Test
	void 진행_상태_통지가_thinking_tool_writing_순서로_나간다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willReturn(AiToolResult.ok("{\"posts\":[]}", 3, List.of("ABC")));
		List<List<String>> chunkScript = List.of(
				List.of(functionCallChunk("list_posts", "{\"brandId\":7}")),
				List.of(textChunk("앞"), textChunk("뒤")));
		BrandAiAgent agent = streamingAgentWith(chunkScript, toolbox, Clock.systemUTC());
		List<String> events = new ArrayList<>();

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "질문")), null, null, "",
				List.of(), orderedListener(events), () -> false);

		assertThat(events).containsExactly("thinking:1", "tool:list_posts#1", "thinking:2", "writing", "delta:앞뒤");
		assertThat(outcome.answer()).isEqualTo("앞뒤");
	}

	/** 강제 답변 턴(toolConfig NONE)은 반대로 델타 도착 즉시 라이브 방출한다. */
	@Test
	void 강제_답변_턴은_텍스트_델타를_도착_즉시_라이브_방출한다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		List<List<String>> chunkScript = List.of(List.of(textChunk("강"), textChunk("제")));
		// 이미 예산이 소진된 시계 - 첫 턴부터 capped(강제 답변) 상태로 만든다.
		BrandAiAgent agent = streamingAgentWith(chunkScript, toolbox, exhaustedBudgetClock());
		List<String> deltas = new ArrayList<>();

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "질문")), null, null, "",
				List.of(), listener(deltas, new ArrayList<>()), () -> false);

		// 청크마다 즉시 나간다 - 합쳐진 텍스트가 끝에 다시 나가면 안 된다(중복 방지).
		assertThat(deltas).containsExactly("강", "제");
		assertThat(outcome.answer()).isEqualTo("강제");
	}

	/** abort 신호가 처음부터 참이면 LLM 호출 자체를 시작하지 않고 즉시 OUTCOME_ABORTED로 멈춘다. */
	@Test
	void abort_신호가_처음부터_참이면_LLM_호출_전에_멈춘다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		AtomicInteger streamCalls = new AtomicInteger();
		ChatTransport transport = new ChatTransport() {
			@Override
			public String post(String jsonBody) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void postStream(String jsonBody, Consumer<String> onData) {
				streamCalls.incrementAndGet();
			}
		};
		BrandAiAgent agent = new BrandAiAgent(new GeminiChatClient(transport, om), toolbox, om, Clock.systemUTC());

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "질문")), null, null, "",
				List.of(), listener(new ArrayList<>(), new ArrayList<>()), () -> true);

		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_ABORTED);
		assertThat(outcome.answer()).isNull();
		assertThat(outcome.answered()).isFalse();
		assertThat(outcome.limitReached()).isNull();
		assertThat(streamCalls.get()).isZero();
	}

	/** abort 신호는 툴 실행 사이사이에도 확인한다 - 한 턴에 함수 호출 2건이 와도 첫 실행 직후 신호가
	 * 참이 되면 두 번째는 실행하지 않고 멈춘다. */
	@Test
	void abort_신호는_툴_실행_사이에도_확인한다() {
		BrandAiToolbox toolbox = mock(BrandAiToolbox.class);
		AtomicBoolean abortAfterFirstTool = new AtomicBoolean(false);
		given(toolbox.execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any()))
				.willAnswer(invocation -> {
					abortAfterFirstTool.set(true);
					return AiToolResult.ok("{\"posts\":[]}", 1, List.of());
				});
		List<List<String>> chunkScript = List.of(List.of(
				functionCallChunk("list_posts", "{\"brandId\":7}"),
				functionCallChunk("list_posts", "{\"brandId\":8}")));
		BrandAiAgent agent = streamingAgentWith(chunkScript, toolbox, Clock.systemUTC());

		BrandAiAgent.AgentOutcome outcome = agent.run(1L, List.of(new AiChatMessage("user", "질문")), null, null, "",
				List.of(), listener(new ArrayList<>(), new ArrayList<>()), abortAfterFirstTool::get);

		assertThat(outcome.outcome()).isEqualTo(AiChatLogEntry.OUTCOME_ABORTED);
		assertThat(outcome.toolCalls()).hasSize(1);
		then(toolbox).should(times(1)).execute(any(BrandAiToolbox.ToolSession.class), anyLong(), anyString(), any());
	}
}
