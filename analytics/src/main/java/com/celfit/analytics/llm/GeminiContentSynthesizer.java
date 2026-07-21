package com.celfit.analytics.llm;

import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/**
 * 해석 문구 전용 Gemini 어댑터 (07-21) — 통합 분석의 <b>파트 B만</b> 떼어낸 것.
 *
 * <p>문구 정의는 {@link GeminiContentAnalyzer}의 통합 프롬프트와 <b>같은 문장을 공유</b>한다
 * (SYNTHESIS_RULES). 복제해 두면 한쪽만 고쳐져 재생성분과 신규분의 의미가 갈린다 —
 * 07-21에 계정 카피 프롬프트가 두 클래스에 복제돼 있던 사고와 같은 함정.
 *
 * <p>사실 추출을 안 하므로 분류표·썸네일이 빠진다. 그만큼 입력 토큰이 작고 이미지 비용이 없다.
 */
public final class GeminiContentSynthesizer implements ContentSynthesisPort {

	static final int MAX_OUTPUT_TOKENS = 2048; // 텍스트 5필드 — 통합(8192)보다 작다

	static final String RESPONSE_SCHEMA = """
			{"type":"object","properties":{
			  "aiContentSummary":{"type":"string"},"contentsPattern":{"type":"string"},
			  "aiCommentInsight":{"type":"string"},"commentAuthenticityGrade":{"type":"string"},
			  "commentAuthenticityNote":{"type":"string"}},
			 "required":["aiContentSummary","contentsPattern","aiCommentInsight",
			  "commentAuthenticityGrade","commentAuthenticityNote"]}""";

	private final GeminiApi api;
	private final Supplier<String> model;
	private final ObjectMapper om = new ObjectMapper();

	public GeminiContentSynthesizer(GeminiApi api, Supplier<String> model) {
		this.api = api;
		this.model = model;
	}

	/** 시스템 프롬프트 — 통합 콜의 파트 B 문구 정의를 그대로 쓴다(단일 원천). */
	public static String instructions() {
		return """
				당신은 뷰티 브랜드 마케터를 위한 인스타그램 콘텐츠 분석가다. 한국어로 답한다.
				콘텐츠에서 이미 추출된 사실("확인된 사실")과 입력의 지표·기준선·댓글 분포 수치만 근거로
				삼고 수치를 지어내지 마라. 사실을 다시 판정하지 말고 주어진 대로 받아들여라.

				%s

				%s""".formatted(GeminiContentAnalyzer.SYNTHESIS_RULES, LlmGuard.RULES);
	}

	/** 유저 입력 — 갱신 잡과 테스트가 공유. */
	public static String userText(ContentToSynthesize c) {
		return """
				콘텐츠: %s (@%s, %s)
				확인된 사실: %s
				지표: views=%s likes=%s comments=%s
				계정 기준선: %s
				댓글 분류 분포: %s

				위 콘텐츠의 해석 문구를 다시 작성하라.""".formatted(c.shortCode(), c.accountHandle(),
				c.contentType(), c.facts(), c.views(), c.likes(), c.comments(),
				c.baseline(), c.commentCategoryCounts());
	}

	@Override
	public Synthesis synthesize(ContentToSynthesize content) {
		String out = api.generateJson(model.get(), instructions(), userText(content), null,
				RESPONSE_SCHEMA, MAX_OUTPUT_TOKENS);
		return GeminiContentAnalyzer.parseSynthesis(om, out);
	}
}
