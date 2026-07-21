package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.celfit.analytics.config.AnalyticsSettings;
import java.util.Set;

/** 종합 텍스트 Anthropic 구현 — 기준선 수치·댓글 분포를 근거로 마케터용 요약을 생성한다. */
public final class AnthropicSynthesizer implements SynthesisPort {

	private static final Set<String> GRADES = Set.of("high", "normal", "suspect");

	private static final String INSTRUCTIONS = """
			당신은 뷰티 브랜드 마케터를 위한 인스타그램 콘텐츠 분석가다. 주어진 수치만 근거로 삼고
			수치를 지어내지 마라. 한국어로, 각 항목 2~3문장 이내.

			- aiContentSummary: 이 콘텐츠가 계정 평균 대비 어땠는지(배수·순위), 반응의 성격(구매 전환형/화제성),
			  협찬 수용도를 종합한 요약
			- contentsPattern: 이 콘텐츠가 최근 12개 평균(계정 기준선) 대비 참여율·좋아요·댓글에서 어느 지점이 두드러지거나 처지는지 한 줄 비교. 기준선 수치를 인용하고, 계정 전체 콘텐츠 경향으로 일반화하지 마라 (07-21 재정의 — GeminiContentAnalyzer와 동일 문구)
			- aiCommentInsight: 댓글 분포 수치를 근거로 반응의 질을 해석
			- commentAuthenticityGrade: high(자연스러운 반응) | normal | suspect(도배·기계적 패턴 의심)
			- commentAuthenticityNote: 판정 근거 한 줄

			%s
			""".formatted(LlmGuard.RULES);

	private final AnthropicClient client;
	private final AnalyticsSettings settings;

	public AnthropicSynthesizer(AnthropicClient client, AnalyticsSettings settings) {
		this.client = client;
		this.settings = settings;
	}

	@Override
	public Synthesis synthesize(ContentToAnalyze content) {
		String input = """
				콘텐츠: %s (@%s, %s)
				캡션: %s
				지표: views=%s likes=%s comments=%s
				계정 기준선: %s
				댓글 분류 분포: %s
				""".formatted(content.shortCode(), content.accountHandle(), content.contentType(),
				content.caption(), content.views(), content.likes(), content.comments(),
				content.baseline(), content.commentCategoryCounts());
		StructuredMessageCreateParams<Synthesis> params = MessageCreateParams.builder()
				.model(settings.llmModel())
				.maxTokens(4096L)
				.system(INSTRUCTIONS)
				.outputConfig(Synthesis.class)
				.addUserMessage(input)
				.build();
		Synthesis s = client.messages().create(params).content().stream()
				.flatMap(block -> block.text().stream())
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("종합 응답에 본문 없음"))
				.text();
		if (!GRADES.contains(s.commentAuthenticityGrade())) {
			s = new Synthesis(s.aiContentSummary(), s.contentsPattern(), s.aiCommentInsight(),
					"normal", s.commentAuthenticityNote());
		}
		return s;
	}
}
