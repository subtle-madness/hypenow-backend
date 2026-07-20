package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.celfit.analytics.config.AnalyticsSettings;
import java.util.List;
import java.util.Set;

/**
 * 댓글 6분류의 Anthropic 구현. structured outputs가 스키마 준수를 보장하고,
 * 어휘 밖 카테고리는 방어적으로 etc로 강제한다 (CHECK 제약과 이중 안전망).
 */
public final class AnthropicCommentClassifier implements CommentClassificationPort {

	private static final Set<String> CATEGORIES =
			Set.of("purchase", "question", "positive", "adAware", "friendTag", "etc");

	private static final String INSTRUCTIONS = """
			당신은 인스타그램 뷰티 콘텐츠의 댓글 분류기다. 각 댓글을 아래 6분류 중 정확히 하나로 분류하라.

			- purchase: 구매 의사·구매처/가격/재입고 질문 ("어디서 사요", "링크 주세요", "가격이요")
			- question: 제품·사용법에 대한 질문 (구매 의도 없이 궁금증 — "건성인데 써도 돼요?")
			- positive: 호감·응원·칭찬 ("피부 미쳤다", "잘 보고 있어요")
			- adAware: 광고임을 인식/언급 ("광고지만", "협찬이구나")
			- friendTag: 친구 태그·같이 보자는 멘션 ("@아무개 이거 봐")
			- etc: 위 어디에도 속하지 않음 (이모지만, 무의미)

			입력의 모든 댓글에 대해 (id, category) 쌍을 빠짐없이 반환하라.
			""";

	/** structured outputs 스키마용 내부 record — 응답 전체 그릇. */
	record Result(List<ClassifiedComment> items) {
	}

	private final AnthropicClient client;
	private final AnalyticsSettings settings;

	public AnthropicCommentClassifier(AnthropicClient client, AnalyticsSettings settings) {
		this.client = client;
		this.settings = settings;
	}

	@Override
	public List<ClassifiedComment> classify(List<CommentToClassify> comments) {
		StringBuilder input = new StringBuilder("댓글 목록:\n");
		for (CommentToClassify c : comments) {
			input.append("- id=").append(c.id()).append(": ").append(c.text()).append('\n');
		}
		StructuredMessageCreateParams<Result> params = MessageCreateParams.builder()
				.model(settings.llmModel())
				.maxTokens(8192L)
				.system(INSTRUCTIONS)
				.outputConfig(Result.class)
				.addUserMessage(input.toString())
				.build();
		Result result = client.messages().create(params).content().stream()
				.flatMap(block -> block.text().stream())
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("분류 응답에 본문 없음"))
				.text();
		return result.items().stream()
				.map(c -> CATEGORIES.contains(c.category()) ? c : new ClassifiedComment(c.id(), "etc"))
				.toList();
	}
}
