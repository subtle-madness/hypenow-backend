package com.celfit.analytics.grouppurchase;

import com.celfit.analytics.llm.GeminiApi;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * 애매분 LLM 판정 어댑터 — 캡션 1건당 1콜(모순이면 최대 2콜), 구조화 JSON 출력(스펙 §3 "LLM(애매분)").
 * 콘텐츠 분석({@link com.celfit.analytics.llm.GeminiContentAnalyzer})과 같은 모델·전송 경로
 * ({@link GeminiApi})을 재사용한다.
 *
 * <p>2026-09-04 정정 3: 운영 검수에서 판매 신호 없는 사용 후기·리뷰·비판 글이 groupPurchase=true로
 * 나오면서 reason은 "리뷰다"·"공구가 아니다" 식으로 판정과 모순되는 사례가 나왔다 — 프롬프트에 후기·
 * 리뷰 배제 지시를 추가하고, 응답의 판정과 사유가 모순되면 1회 재질의(그래도 모순이면 거짓 처리)하는
 * 가드를 더한다.
 */
public final class GeminiGroupPurchaseJudge implements GroupPurchaseJudgePort {

	private static final Logger log = LoggerFactory.getLogger(GeminiGroupPurchaseJudge.class);

	/** 판정 1건은 boolean+한 줄 근거뿐이라 짧게 잡는다. */
	static final int MAX_OUTPUT_TOKENS = 512;

	static final String RESPONSE_SCHEMA = """
			{"type":"object","properties":{
			  "groupPurchase":{"type":"boolean"},"reason":{"type":"string"}},
			 "required":["groupPurchase","reason"]}""";

	/** 시스템 지시 — 스펙 §3 질문 문구 + 2026-09-04 정정 3(판매 신호 없는 후기·리뷰 배제). */
	static final String INSTRUCTIONS = """
			당신은 인스타그램 게시물 캡션을 보고 이 게시물이 인플루언서 공동구매(공구) 판매
			게시물인지 판정한다. `공구`가 연장·도구 의미로만 쓰였으면 공동구매가 아니다.
			판매·모집·오픈·마감·기간·구매 링크(주문서) 안내 같은 판매 신호가 없는 사용 후기·리뷰·비판·
			비교 글은 공동구매 게시물이 아니다(false). 남이 판 공구 제품을 써본 후기도 false. true일
			때는 reason에 캡션의 판매 신호 문구를 인용한다.
			캡션에 없는 사실을 추론해 단정하지 마라. 한국어로 답한다.
			출력은 groupPurchase(boolean)와 reason(판정 근거 한 줄) 두 필드다.""";

	/**
	 * 판정(true)과 사유가 모순되는 경우 — "도구가 아닌 공동구매 의미"처럼 긍정 근거에도 '도구'가
	 * 자연스럽게 등장하므로 '도구' 단독은 넣지 않는다.
	 */
	private static final Pattern CONTRADICTORY_REASON = Pattern.compile(
			"연장|도구 ?(의미|뜻)|공구가 ?아니|공구 ?아님|공동구매가 ?아니|판매 ?(신호|안내|정보)가 ?없|후기|리뷰|사용기|비판|비교");

	private final GeminiApi api;
	private final Supplier<String> model;
	private final ObjectMapper om = new ObjectMapper();

	public GeminiGroupPurchaseJudge(GeminiApi api, Supplier<String> model) {
		this.api = api;
		this.model = model;
	}

	@Override
	public Judgment judge(String caption) {
		Judgment judgment = parse(om, api.generateJson(model.get(), INSTRUCTIONS, userText(caption),
				null, RESPONSE_SCHEMA, MAX_OUTPUT_TOKENS));
		if (!isContradictory(judgment)) {
			return judgment;
		}

		String retryUser = userText(caption) + "\n이전 응답은 groupPurchase=true였으나 reason(%s)이 판매 게시물이 아니라는 근거다. 캡션에 판매 신호(오픈·마감·기간·가격·주문 링크·모집)가 실제로 있으면 그 문구를 인용해 true, 없으면 false로 다시 판정하라."
				.formatted(judgment.reason());
		Judgment retried = parse(om, api.generateJson(model.get(), INSTRUCTIONS, retryUser, null,
				RESPONSE_SCHEMA, MAX_OUTPUT_TOKENS));
		if (isContradictory(retried)) {
			log.warn("공동구매 LLM 사유/판정 모순 가드 발동 — 재질의도 모순이라 거짓 처리. 캡션: {}",
					truncate(caption));
			return new Judgment(false, retried.reason() + " [모순 가드: 사유가 부정 근거라 거짓 처리]");
		}
		return retried;
	}

	private static boolean isContradictory(Judgment judgment) {
		return judgment.groupPurchase() && CONTRADICTORY_REASON.matcher(judgment.reason()).find();
	}

	private static String truncate(String caption) {
		if (caption == null || caption.isBlank()) {
			return "(없음)";
		}
		return caption.length() <= 60 ? caption : caption.substring(0, 60) + "...";
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
