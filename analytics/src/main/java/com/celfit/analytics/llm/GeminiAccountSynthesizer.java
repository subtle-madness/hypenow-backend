package com.celfit.analytics.llm;

import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/** 계정 카피 Gemini 어댑터 — 카피 7종 1콜. 프롬프트·GUARD는 골드셋(07-18) 문구 검증 통과본. */
public final class GeminiAccountSynthesizer implements AccountSynthesisPort {

	static final int MAX_OUTPUT_TOKENS = 4096;

	static final String RESPONSE_SCHEMA = """
			{"type":"object","properties":{
			  "tagline":{"type":"string"},"summary":{"type":"string"},"trendNote":{"type":"string"},
			  "chartNote":{"type":"string"},"traits":{"type":"array","items":{"type":"string"}},
			  "adHeadline":{"type":"string"},"paceNote":{"type":"string"}},
			 "required":["tagline","summary","trendNote","chartNote","traits","adHeadline","paceNote"]}""";

	static final String INSTRUCTIONS = """
			당신은 뷰티 브랜드 마케터를 위한 인스타그램 인플루언서 분석가다. 주어진 수치·캡션만
			근거로 삼고 수치를 지어내지 마라. 한국어. 화면에 그대로 노출되는 짧은 문구이므로 분량을 지켜라.

			- tagline: 프로필 헤더 한 줄 소개 — 콘텐츠 성격·톤 (예: "저자극 스킨케어 중심 · 성분과 사용감을 짚는 정보형 리뷰 톤"). 40자 이내
			- summary: 마케터 관점의 계정 분석 요약, 3~4문장
			- trendNote: 최근 흐름 한 문장 (trend_direction·trend_change_pct 근거)
			- chartNote: 게시물별 성과 분포의 특징 한 문장 (예: "잘 터진 3개가 평균을 끌어올림")
			- traits: 콘텐츠 성향 태그 3~5개, 각 2~6자 명사구
			- adHeadline: 이 계정의 광고 활동을 요약한 한 문장. 입력의 "광고 활동" 값에 따라 아래처럼 쓴다.
			  어느 경우든 **좋다·나쁘다·유리하다·적합하다 같은 평가나 권유는 쓰지 마라** — 수치와 사실만
			  객관적으로 진술하고 판단은 읽는 사람에게 맡긴다.
			  · "비교 가능": organic 평균과 협찬 평균의 차이를 수치로 (organic_avg·ad_avg·ad_drop_pct 근거)
			  · "협찬 없음": 협찬 표기 게시물이 없다는 사실과 해당 기간의 지표를 그대로 진술
			  · "전량 협찬": 협찬 건수·비중과 그 성과 수치를 진술하고, 비교할 organic이 없다는 점을 밝힌다
			  · "판단 불가": 빈 문자열
			- paceNote: 업로드 페이스 한 문장 (avg_interval_days 근거, 예: "주 2~3회 올리는 페이스")

			%s""".formatted(LlmGuard.RULES);

	private final GeminiApi api;
	private final Supplier<String> model;
	private final ObjectMapper om = new ObjectMapper();

	public GeminiAccountSynthesizer(GeminiApi api, Supplier<String> model) {
		this.api = api;
		this.model = model;
	}

	/** 시스템 프롬프트 — 구독 버스트 러너(ClaudeBurstRunner)도 같은 검증 통과본을 쓴다. */
	public static String instructions() {
		return INSTRUCTIONS;
	}

	/** 유저 입력 — synthesize와 버스트 export가 공유 (프롬프트 정합 단일 원천). */
	public static String userText(AccountToAnalyze account) {
		return """
				계정: @%s (광고 활동: %s)
				계정 지표: %s
				카테고리 믹스: %s
				게시물(올린 순, 캡션은 앞부분만): %s
				""".formatted(account.handle(), account.adSituation().label(),
				account.summary(), account.categoryStats(), account.posts());
	}

	@Override
	public AccountCopy synthesize(AccountToAnalyze account) {
		String out = api.generateJson(model.get(), INSTRUCTIONS, userText(account), null,
				RESPONSE_SCHEMA, MAX_OUTPUT_TOKENS);
		return om.readValue(out, AccountCopy.class);
	}
}
