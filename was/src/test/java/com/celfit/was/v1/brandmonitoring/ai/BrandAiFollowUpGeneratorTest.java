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

	/** F4(2026-08-30 리뷰) - 모델이 스키마·프롬프트 규칙을 어기고 kind에 미지 값을 넣어도 그 항목만
	 * 버려지고 나머지는 살아남는다(서버측 계약 강제). */
	@Test
	void kind가_deepen_action이_아니면_그_항목만_제외한다() {
		GeminiChatClient client = new GeminiChatClient(body -> followUpsResponse(
				"[{\"text\":\"정상 질문\",\"kind\":\"deepen\"},{\"text\":\"이상한 질문\",\"kind\":\"unknown\"}]"), om);
		BrandAiFollowUpGenerator generator = new BrandAiFollowUpGenerator(client, om);

		List<AiMessagesResponse.FollowUp> result = generator.generate("질문", "답변");

		assertThat(result).hasSize(1);
		assertThat(result.get(0).text()).isEqualTo("정상 질문");
	}

	/** F4 - 모델이 규칙(정확히 2개)을 어기고 3개 이상 만들어도 서버가 앞에서부터 2개로 자른다. */
	@Test
	void 결과가_3개_이상_생성돼도_2개로_절단한다() {
		GeminiChatClient client = new GeminiChatClient(body -> followUpsResponse(
				"[{\"text\":\"질문1\",\"kind\":\"deepen\"},{\"text\":\"질문2\",\"kind\":\"action\"},"
						+ "{\"text\":\"질문3\",\"kind\":\"deepen\"}]"), om);
		BrandAiFollowUpGenerator generator = new BrandAiFollowUpGenerator(client, om);

		List<AiMessagesResponse.FollowUp> result = generator.generate("질문", "답변");

		assertThat(result).hasSize(2);
		assertThat(result).extracting(AiMessagesResponse.FollowUp::text).containsExactly("질문1", "질문2");
	}

	/**
	 * F3(2026-08-30 리뷰) - 컨트롤러가 넘긴 남은 예산이 5초보다 짧으면 그 예산 안에서만 기다린다.
	 * 전송이 예산보다 오래 걸리면 타임아웃으로 빈 배열로 접힌다(기존 실패 관용 경로와 동일 결과지만,
	 * 원인이 "5초 타임아웃"이 아니라 "짧아진 예산 타임아웃"이라는 게 다르다).
	 */
	@Test
	void 남은_예산이_짧으면_그_안에서만_기다리다_타임아웃으로_빈_배열을_접는다() {
		GeminiChatClient client = new GeminiChatClient(body -> {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return followUpsResponse("[{\"text\":\"질문\",\"kind\":\"deepen\"}]");
		}, om);
		BrandAiFollowUpGenerator generator = new BrandAiFollowUpGenerator(client, om);

		List<AiMessagesResponse.FollowUp> result = generator.generate("질문", "답변", 100L);

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
