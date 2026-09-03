package com.celfit.analytics.grouppurchase;

import com.celfit.analytics.llm.GeminiApi;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/**
 * 애매분 LLM 판정 어댑터 — 캡션 1건당 1콜, 구조화 JSON 출력(스펙 §3 "LLM(애매분)").
 * 콘텐츠 분석({@link com.celfit.analytics.llm.GeminiContentAnalyzer})과 같은 모델·전송 경로
 * ({@link GeminiApi})을 재사용한다.
 */
public final class GeminiGroupPurchaseJudge implements GroupPurchaseJudgePort {

	/** 판정 1건은 boolean+한 줄 근거뿐이라 짧게 잡는다. */
	static final int MAX_OUTPUT_TOKENS = 512;

	static final String RESPONSE_SCHEMA = """
			{"type":"object","properties":{
			  "groupPurchase":{"type":"boolean"},"reason":{"type":"string"}},
			 "required":["groupPurchase","reason"]}""";

	/** 시스템 지시 — 스펙 §3 질문 문구 그대로. */
	static final String INSTRUCTIONS = """
			당신은 인스타그램 게시물 캡션을 보고 이 게시물이 인플루언서 공동구매(공구) 판매
			게시물인지 판정한다. `공구`가 연장·도구 의미로만 쓰였으면 공동구매가 아니다.
			캡션에 없는 사실을 추론해 단정하지 마라. 한국어로 답한다.
			출력은 groupPurchase(boolean)와 reason(판정 근거 한 줄) 두 필드다.""";

	private final GeminiApi api;
	private final Supplier<String> model;
	private final ObjectMapper om = new ObjectMapper();

	public GeminiGroupPurchaseJudge(GeminiApi api, Supplier<String> model) {
		this.api = api;
		this.model = model;
	}

	@Override
	public Judgment judge(String caption) {
		String out = api.generateJson(model.get(), INSTRUCTIONS, userText(caption), null,
				RESPONSE_SCHEMA, MAX_OUTPUT_TOKENS);
		return parse(om, out);
	}

	static String userText(String caption) {
		return "캡션: %s".formatted(caption == null || caption.isBlank() ? "(없음)" : caption);
	}

	/** 응답 JSON → Judgment — 필드 누락·형식 오류는 예외로(잡이 격리해 다음 실행 재시도). */
	static Judgment parse(ObjectMapper om, String json) {
		Output o = om.readValue(json, Output.class);
		if (o.groupPurchase() == null || o.reason() == null) {
			throw new IllegalStateException("공동구매 LLM 응답 형식 오류(필드 누락): " + json);
		}
		return new Judgment(o.groupPurchase(), o.reason());
	}

	record Output(Boolean groupPurchase, String reason) {}
}
