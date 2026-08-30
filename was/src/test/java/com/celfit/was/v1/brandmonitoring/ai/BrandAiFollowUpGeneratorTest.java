package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 후속 질문 생성 검증(FE 변경요청서 §3.3) - 전송은 스크립트 fake로 대체한다. 타임아웃(5초) 자체는
 * 실시간으로 재현하면 테스트가 느려지므로, 전송 실패·파싱 불가가 빈 배열로 접히는지로 같은 관용구
 * (실패 관용)를 검증한다.
 */
class BrandAiFollowUpGeneratorTest {

	private final ObjectMapper om = new ObjectMapper();

	private static String followUpsResponse(String innerJson) {
		String escaped = innerJson.replace("\"", "\\\"");
		return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + escaped + "\"}]}}]}";
	}

	@Test
	void 정상_응답을_followUp_2개로_파싱한다() {
		GeminiChatClient client = new GeminiChatClient(body -> followUpsResponse(
				"[{\"text\":\"다른 게시물도 볼까요?\",\"kind\":\"deepen\"},"
						+ "{\"text\":\"DM 초안을 만들까요?\",\"kind\":\"action\"}]"), om);
		BrandAiFollowUpGenerator generator = new BrandAiFollowUpGenerator(client, om);

		List<AiMessagesResponse.FollowUp> result = generator.generate("질문", "답변");

		assertThat(result).hasSize(2);
		assertThat(result.get(0).kind()).isEqualTo("deepen");
		assertThat(result.get(1).kind()).isEqualTo("action");
	}

	@Test
	void 전송_실패는_빈_배열로_접는다() {
		GeminiChatClient client = new GeminiChatClient(body -> {
			throw new IllegalStateException("Vertex HTTP 500");
		}, om);
		BrandAiFollowUpGenerator generator = new BrandAiFollowUpGenerator(client, om);

		List<AiMessagesResponse.FollowUp> result = generator.generate("질문", "답변");

		assertThat(result).isEmpty();
	}

	@Test
	void 응답_본문이_배열이_아니면_빈_배열로_접는다() {
		GeminiChatClient client = new GeminiChatClient(body -> followUpsResponse("{\"oops\":true}"), om);
		BrandAiFollowUpGenerator generator = new BrandAiFollowUpGenerator(client, om);

		List<AiMessagesResponse.FollowUp> result = generator.generate("질문", "답변");

		assertThat(result).isEmpty();
	}

	@Test
	void 후보_자체가_없으면_빈_배열로_접는다() {
		GeminiChatClient client = new GeminiChatClient(
				body -> "{\"candidates\":[],\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}", om);
		BrandAiFollowUpGenerator generator = new BrandAiFollowUpGenerator(client, om);

		List<AiMessagesResponse.FollowUp> result = generator.generate("질문", "답변");

		assertThat(result).isEmpty();
	}

	@Test
	void 요청_본문에_질문과_답변이_실린다() {
		java.util.concurrent.atomic.AtomicReference<String> sent = new java.util.concurrent.atomic.AtomicReference<>();
		GeminiChatClient client = new GeminiChatClient(body -> {
			sent.set(body);
			return followUpsResponse("[]");
		}, om);
		BrandAiFollowUpGenerator generator = new BrandAiFollowUpGenerator(client, om);

		generator.generate("이 브랜드 인기 게시물 알려줘", "TOP1이 가장 조회수가 높아요");

		assertThat(sent.get()).contains("이 브랜드 인기 게시물 알려줘").contains("TOP1이 가장 조회수가 높아요");
	}
}
