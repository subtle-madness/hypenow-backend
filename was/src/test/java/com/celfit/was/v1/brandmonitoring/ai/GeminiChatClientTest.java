package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Vertex generateContent 요청 조립·응답 파싱 검증 - 전송은 스크립트 fake로 대체한다(설계 §9). */
class GeminiChatClientTest {

	private final ObjectMapper om = new ObjectMapper();

	@Test
	void 시스템프롬프트와_툴선언을_요청_본문에_싣는다() {
		List<String> sent = new ArrayList<>();
		GeminiChatClient client = new GeminiChatClient(body -> {
			sent.add(body);
			return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"안녕하세요\"}]}}]}";
		}, om);

		client.generate("너는 분석 어시스턴트다", List.of(client.userContent("안녕")),
				List.of(new AiToolSpec("list_brands", "브랜드 목록", null),
						new AiToolSpec("get_post", "게시물 상세",
								"{\"type\":\"object\",\"properties\":{\"shortCode\":{\"type\":\"string\"}}}")));

		JsonNode body = om.readTree(sent.get(0));
		assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asString())
				.isEqualTo("너는 분석 어시스턴트다");
		assertThat(body.path("contents").path(0).path("role").asString()).isEqualTo("user");
		JsonNode declarations = body.path("tools").path(0).path("functionDeclarations");
		assertThat(declarations.size()).isEqualTo(2);
		assertThat(declarations.path(0).has("parameters")).isFalse();
		assertThat(declarations.path(1).path("parameters").path("properties").has("shortCode")).isTrue();
	}

	@Test
	void thinkingBudget을_0으로_고정한다() {
		List<String> sent = new ArrayList<>();
		GeminiChatClient client = new GeminiChatClient(body -> {
			sent.add(body);
			return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"끝\"}]}}]}";
		}, om);

		client.generate("시스템", List.of(client.userContent("질문")), List.of());

		// I7 - gemini-2.5 dynamic thinking이 maxOutputTokens를 잠식해 MAX_TOKENS로 빈 응답이 되는 것을 막는다
		assertThat(om.readTree(sent.get(0)).path("generationConfig").path("thinkingConfig")
				.path("thinkingBudget").asInt()).isEqualTo(0);
	}

	@Test
	void 강제답변_모드에서는_tools를_유지하되_toolConfig로_호출만_막는다() {
		List<String> sent = new ArrayList<>();
		GeminiChatClient client = new GeminiChatClient(body -> {
			sent.add(body);
			return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"끝\"}]}}]}";
		}, om);

		client.generate("시스템", List.of(client.userContent("질문")),
				List.of(new AiToolSpec("list_brands", "브랜드 목록", null)), true);

		// I8 - tools를 통째로 빼면 이전 턴의 functionCall/functionResponse 파트가 남은 히스토리와
		// 조합돼 Vertex 400 위험이 있다. tools는 유지하고 toolConfig mode만 NONE으로 막는다.
		JsonNode body = om.readTree(sent.get(0));
		assertThat(body.path("tools").path(0).path("functionDeclarations").size()).isEqualTo(1);
		assertThat(body.path("toolConfig").path("functionCallingConfig").path("mode").asString())
				.isEqualTo("NONE");
	}

	@Test
	void finishReason과_thoughtsTokenCount를_읽는다() {
		GeminiChatClient client = new GeminiChatClient(body -> """
				{"candidates":[{"finishReason":"MAX_TOKENS","content":{"role":"model","parts":[]}}],
				 "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":0,"thoughtsTokenCount":2048}}
				""", om);

		LlmTurn turn = client.generate("시스템", List.of(client.userContent("질문")), List.of());

		assertThat(turn.finishReason()).isEqualTo("MAX_TOKENS");
		assertThat(turn.text()).isEmpty();
		// I7-④ thinking 토큰을 outputTokens에 합산한다
		assertThat(turn.outputTokens()).isEqualTo(2048);
	}

	@Test
	void 후보_자체가_없으면_blockReason을_finishReason으로_대신한다() {
		GeminiChatClient client = new GeminiChatClient(body -> """
				{"candidates":[],"promptFeedback":{"blockReason":"SAFETY"},
				 "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":0}}
				""", om);

		LlmTurn turn = client.generate("시스템", List.of(client.userContent("질문")), List.of());

		assertThat(turn.finishReason()).isEqualTo("SAFETY");
	}

	@Test
	void 툴이_비면_tools_필드를_아예_싣지_않는다() {
		List<String> sent = new ArrayList<>();
		GeminiChatClient client = new GeminiChatClient(body -> {
			sent.add(body);
			return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"끝\"}]}}]}";
		}, om);

		client.generate("시스템", List.of(client.userContent("질문")), List.of());

		assertThat(om.readTree(sent.get(0)).has("tools")).isFalse();
	}

	@Test
	void 함수호출_응답을_ToolCall로_파싱한다() {
		GeminiChatClient client = new GeminiChatClient(body -> """
				{"candidates":[{"content":{"role":"model","parts":[
				  {"functionCall":{"name":"list_posts","args":{"brandId":7,"days":30}}}]}}],
				 "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":15}}
				""", om);

		LlmTurn turn = client.generate("시스템", List.of(client.userContent("질문")), List.of());

		assertThat(turn.text()).isEmpty();
		assertThat(turn.toolCalls()).hasSize(1);
		assertThat(turn.toolCalls().get(0).name()).isEqualTo("list_posts");
		assertThat(turn.toolCalls().get(0).args().path("brandId").asInt()).isEqualTo(7);
		assertThat(turn.promptTokens()).isEqualTo(120);
		assertThat(turn.outputTokens()).isEqualTo(15);
	}

	@Test
	void 텍스트_응답은_파트를_이어붙인다() {
		GeminiChatClient client = new GeminiChatClient(body -> """
				{"candidates":[{"content":{"parts":[{"text":"앞"},{"text":"뒤"}]}}]}
				""", om);

		assertThat(client.generate("시스템", List.of(client.userContent("질문")), List.of()).text())
				.isEqualTo("앞뒤");
	}

	/** 구조화 출력 1콜(followUps 생성 전용, FE §3.3) - tools 없이 responseSchema·responseMimeType만 싣는다. */
	@Test
	void generateStructured는_tools_없이_responseSchema를_싣는다() {
		List<String> sent = new ArrayList<>();
		GeminiChatClient client = new GeminiChatClient(body -> {
			sent.add(body);
			return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"[]\"}]}}]}";
		}, om);
		JsonNode schema = om.readTree("{\"type\":\"array\"}");

		client.generateStructured("시스템 지시", "질문: 안녕\n답변: 반가워요", schema, 256);

		JsonNode body = om.readTree(sent.get(0));
		assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asString())
				.isEqualTo("시스템 지시");
		assertThat(body.path("contents").path(0).path("role").asString()).isEqualTo("user");
		assertThat(body.path("contents").path(0).path("parts").path(0).path("text").asString())
				.contains("질문: 안녕");
		assertThat(body.has("tools")).isFalse();
		assertThat(body.path("generationConfig").path("responseMimeType").asString())
				.isEqualTo("application/json");
		assertThat(body.path("generationConfig").path("responseSchema").path("type").asString())
				.isEqualTo("array");
		assertThat(body.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(256);
	}

	/** SSE data 청크를 하나씩 흘려주는 fake 전송(T2 테스트) - postStream만 오버라이드하고 post()는
	 * 이 테스트들에서 쓰지 않으므로 미지원 예외를 던지는 기본 구현 그대로 둔다. */
	private static ChatTransport streamingFake(List<String> chunksJson, List<String> sentBodies) {
		return new ChatTransport() {
			@Override
			public String post(String jsonBody) {
				throw new UnsupportedOperationException("이 테스트는 postStream만 쓴다");
			}

			@Override
			public void postStream(String jsonBody, Consumer<String> onData) {
				sentBodies.add(jsonBody);
				chunksJson.forEach(onData::accept);
			}
		};
	}

	@Test
	void 스트리밍_텍스트_델타를_청크마다_콜백하고_최종_텍스트로_합성한다() {
		List<String> sent = new ArrayList<>();
		List<String> deltas = new ArrayList<>();
		ChatTransport transport = streamingFake(List.of(
				"{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"안\"}]}}]}",
				"{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"녕\"}]}}],"
						+ "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5}}",
				"{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"role\":\"model\",\"parts\":[]}}]}"),
				sent);
		GeminiChatClient client = new GeminiChatClient(transport, om);

		LlmTurn turn = client.generateStream("시스템", List.of(client.userContent("질문")), List.of(), false,
				chunk -> deltas.add(chunk.textDelta()));

		assertThat(deltas).containsExactly("안", "녕", "");
		assertThat(turn.text()).isEqualTo("안녕");
		assertThat(turn.finishReason()).isEqualTo("STOP");
		assertThat(turn.promptTokens()).isEqualTo(10);
		assertThat(turn.outputTokens()).isEqualTo(5);
		// 요청 본문 조립은 generate()와 동일하다(공통 buildBody 재사용).
		assertThat(om.readTree(sent.get(0)).path("generationConfig").path("thinkingConfig")
				.path("thinkingBudget").asInt()).isEqualTo(0);
	}

	@Test
	void 스트리밍_함수호출_청크를_ToolCall로_합성한다() {
		List<String> sent = new ArrayList<>();
		List<List<LlmTurn.ToolCall>> deltaCalls = new ArrayList<>();
		ChatTransport transport = streamingFake(List.of(
				"{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
						+ "{\"functionCall\":{\"name\":\"list_posts\",\"args\":{\"brandId\":7}}}]}}],"
						+ "\"usageMetadata\":{\"promptTokenCount\":20,\"candidatesTokenCount\":8}}"),
				sent);
		GeminiChatClient client = new GeminiChatClient(transport, om);

		LlmTurn turn = client.generateStream("시스템", List.of(client.userContent("질문")), List.of(), false,
				chunk -> deltaCalls.add(chunk.toolCalls()));

		assertThat(turn.toolCalls()).hasSize(1);
		assertThat(turn.toolCalls().get(0).name()).isEqualTo("list_posts");
		assertThat(deltaCalls).hasSize(1);
		assertThat(deltaCalls.get(0)).hasSize(1);
	}

	@Test
	void 강제답변_스트리밍도_tools를_유지하되_toolConfig로_호출만_막는다() {
		List<String> sent = new ArrayList<>();
		ChatTransport transport = streamingFake(
				List.of("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"끝\"}]}}]}"), sent);
		GeminiChatClient client = new GeminiChatClient(transport, om);

		client.generateStream("시스템", List.of(client.userContent("질문")),
				List.of(new AiToolSpec("list_brands", "브랜드 목록", null)), true, chunk -> { });

		JsonNode body = om.readTree(sent.get(0));
		assertThat(body.path("tools").path(0).path("functionDeclarations").size()).isEqualTo(1);
		assertThat(body.path("toolConfig").path("functionCallingConfig").path("mode").asString())
				.isEqualTo("NONE");
	}

	@Test
	void 툴_결과_컨텐츠는_functionResponse_파트로_조립된다() {
		GeminiChatClient client = new GeminiChatClient(
				body -> "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"끝\"}]}}]}", om);

		JsonNode content = client.toolResultContent(
				List.of(new GeminiChatClient.ToolResponse("get_post", "{\"shortCode\":\"ABC\"}")));

		assertThat(content.path("role").asString()).isEqualTo("user");
		JsonNode fr = content.path("parts").path(0).path("functionResponse");
		assertThat(fr.path("name").asString()).isEqualTo("get_post");
		assertThat(fr.path("response").path("result").path("shortCode").asString()).isEqualTo("ABC");
	}
}
