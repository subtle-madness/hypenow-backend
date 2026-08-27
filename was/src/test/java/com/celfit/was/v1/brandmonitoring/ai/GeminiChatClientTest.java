package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
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
