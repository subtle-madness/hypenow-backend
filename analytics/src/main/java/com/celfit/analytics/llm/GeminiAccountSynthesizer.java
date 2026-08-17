package com.celfit.analytics.llm;

import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/** 계정 카피 Gemini 어댑터 — 카피 5종 1콜(07-27 개편). 07-18 골드셋 검증 통과본에서 재작성 — 재검증은 Task 12. */
public final class GeminiAccountSynthesizer implements AccountSynthesisPort {

	public static final int MAX_OUTPUT_TOKENS = 4096;

	public static final String RESPONSE_SCHEMA = """
			{"type":"object","properties":{
			  "tagline":{"type":"string"},"traits":{"type":"array","items":{"type":"string"}},
			  "perfSummary":{"type":"string"},"contentSummary":{"type":"string"},
			  "adSummary":{"type":"string"}},
			 "required":["tagline","traits","perfSummary","contentSummary","adSummary"]}""";

	static final String INSTRUCTIONS_TEMPLATE = """
			당신은 뷰티 브랜드 마케터를 위한 인스타그램 인플루언서 분석가다. 주어진 수치·캡션만
			근거로 삼고 수치를 지어내지 마라. 한국어. 화면에 그대로 노출되는 짧은 문구이므로 분량을 지켜라.

			- tagline: 프로필 헤더 한 줄 소개. 무엇을 다루는 계정인지에 더해 말투·전개 방식·설득
			  방식(예: 성분 근거 제시, 사용 전후 비교, 브이로그형)까지 구체적으로. "·"로 구획, 70자 이내
			  (예: "저자극 스킨케어 중심 · 성분표를 짚어가며 사용 전후 비교로 설득하는 정보형 리뷰 톤")
			- traits: 콘텐츠 성향 태그 3~5개 — 아래 어휘에서만 고른다(임의 조어·변형 금지, 표기 그대로).
			  계정을 가장 잘 설명하는 원자 태그를 조합한다(예: 감성 브이로그 계정이면 "브이로그"와 "감성 무드" 2개).
			  어휘:
			%s
			- perfSummary: 성과 요약 2~3문장 — 지표의 수준(팔로워 대비), 최근 흐름, 포맷(릴스/피드)별
			  반응 차이 중심. median_views·median_er_pct가 입력에 있으면 그게 해당 지표 수준 판정의
			  근거다(둘 다 있을 때 avg_views·avg_er_pct와 비교해 고르는 게 아니라, 있는 값이 곧
			  근거다). 그 지표에 median 없이 avg_views·avg_er_pct만 있으면 그게 근거다.
			  avg_likes·avg_comments·trend_direction·trend_change_pct와 게시물 목록
			  (content_type·views·likes)도 근거로 쓴다 — 포맷별 반응 차이는 게시물 목록에서만
			  확인 가능하니 그 밖은 단정하지 마라.
			  **아래 "계정 지표"·"게시물" 입력에 특정 지표 키가 아예 없으면 그 지표는 표본이 부족해서
			  데이터 자체가 제공되지 않은 것이다 — "언급하지 말라는 지표"가 아니라 "가진 게 없는 지표"로
			  여기고, 다른 지표에서 없는 값을 유추하거나 대신 채워 넣지 마라.**
			  **구체 수치를 문장에 그대로 인용하지 마라** — 수치 정본은 화면 스탯 타일이고 이 문장은
			  매일 갱신되지 않아 며칠 뒤 낡는다. "팔로워 대비 높은 편", "완만한 상승세",
			  "릴스 반응이 뚜렷이 좋다"처럼 수준·방향 표현만 쓴다.
			%s
			- contentSummary: 콘텐츠 성격 요약 2~3문장 — 무엇을 어떤 방식으로 다루는지, 반복되는 형식·톤
			  (카테고리 믹스·캡션 근거)
			- adSummary: 광고 활동 요약 2~3문장. 입력의 "광고 활동" 값에 따라 아래처럼 쓴다.
			  어느 경우든 **좋다·나쁘다·유리하다·적합하다 같은 평가나 권유는 쓰지 마라** — 수치와 사실만
			  객관적으로 진술하고 판단은 읽는 사람에게 맡긴다.
			  · "비교 가능": organic 평균과 협찬 평균의 차이, 광고에서의 톤 변화 여부를 수치·캡션 근거로
			  · "협찬 없음": 협찬 표기 게시물이 없다는 사실과 해당 기간의 지표를 그대로 진술
			  · "전량 협찬": 협찬 건수·비중과 그 성과 수치를 진술하고, 비교할 organic이 없다는 점을 밝힌다
			  · "판단 불가": 빈 문자열

			%s""";

	private final GeminiApi api;
	private final Supplier<String> model;
	private final Supplier<TraitTaxonomy> vocab;
	private final ObjectMapper om = new ObjectMapper();

	public GeminiAccountSynthesizer(GeminiApi api, Supplier<String> model, Supplier<TraitTaxonomy> vocab) {
		this.api = api;
		this.model = model;
		this.vocab = vocab;
	}

	/**
	 * 시스템 프롬프트 — 성과 요약 통계 왜곡 가드(설계 2026-07-30) 판정을 반영한 신뢰도 지침 포함.
	 * traits는 어휘 내 선택(2026-07-29 어휘 통제 스펙) — 어휘 블록은 V41 시드 스냅샷을 주입한다.
	 */
	public static String instructions(TraitTaxonomy vocab, PerfConfidence confidence) {
		// 계정 카피는 LlmGuard.ACCOUNT_RULES를 쓴다(RULES 아님) — RULES의 "근거 수치를 함께
		// 인용하라"는 콘텐츠 경로 전용 지시라 이 클래스의 perfSummary 절(수치 인용 금지)과
		// 정면 충돌한다(2026-07-30 test 실측). Anthropic 어댑터도 이 메서드를 그대로 호출하므로
		// 프로바이더 양쪽이 함께 고쳐진다.
		return INSTRUCTIONS_TEMPLATE.formatted(vocab.promptBlock().indent(2).stripTrailing(),
				confidence.promptBlock(), LlmGuard.ACCOUNT_RULES);
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
		String out = api.generateJson(model.get(), instructions(vocab.get(), account.confidence()),
				userText(account), null, RESPONSE_SCHEMA, MAX_OUTPUT_TOKENS);
		return parse(om, out);
	}

	/** 응답 JSON → AccountCopy — 온라인(synthesize)·배치 수거(AccountBatchLines)가 공유하는 단일 파서. */
	public static AccountCopy parse(ObjectMapper om, String json) {
		return om.readValue(json, AccountCopy.class);
	}
}
