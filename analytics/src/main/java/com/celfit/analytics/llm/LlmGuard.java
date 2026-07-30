package com.celfit.analytics.llm;

/**
 * 문구 생성(③종합·④계정 카피) 절제 규칙 — 골드셋(07-18)에서 조언 2→0건·얇은 표본 헤지 7/7 검증된
 * 문구 그대로. 프로바이더 공통이라 프롬프트 조립부가 공유한다. 문구 수정 시 골드셋 재검 권장.
 *
 * <p><b>근거 수치 인용 지시는 콘텐츠 경로 전용이다({@link #BODY}/{@link #RULES}).</b> 콘텐츠 해석
 * 문구는 특정 게시물 1건의 사실을 다루므로 수치가 낡지 않지만, 계정 카피는 캐시되어 며칠간
 * 서빙되므로 {@code GeminiAccountSynthesizer}의 perfSummary 절이 "수치를 문장에 그대로 인용하지
 * 마라"를 명시한다 — 이 지시와 정면 충돌한다. test 스테이징 실측(2026-07-30, 계정 {@code 0_tsuki2})에서
 * "평균 좋아요 수는 1,605개 수준"처럼 LLM이 이 근거 수치 지시 쪽을 따라 낡는 값을 프롬프트에 박은
 * 사례가 나와 {@link #ACCOUNT_BODY}/{@link #ACCOUNT_RULES}로 계정 카피 경로만 이 줄을 뺀다
 * (설계: docs/superpowers/specs/2026-07-30-perf-summary-statistical-guards-design.md).
 */
public final class LlmGuard {

	/** 콘텐츠·계정 카피 공통 3줄 — 표본 헤지·추론 금지·조언 금지. */
	private static final String COMMON = """
			- 분석 표본이 3건 미만이면 성과·패턴·추이를 단정하지 말고 "표본이 부족해 판단하기 어렵다"를 명시하라.
			- 입력에 없는 사실·패턴·경향을 추론해 단정하지 마라. 평균과 값이 같은 이유가 표본 1건 때문이면 "안정적"이라 표현하지 마라.
			- 조언·제안·전략 제시는 금지다. 관찰과 해석만 쓴다 ("~가 필요합니다", "~하는 전략" 금지).""";

	/** 규칙 본문(콘텐츠 경로 전용) — 헤더는 문맥별(단독 프롬프트/통합 콜 파트 B)로 붙인다. */
	public static final String BODY = COMMON + "\n- 핵심 주장에는 근거 수치를 함께 인용하라.";

	public static final String RULES = "[절제 규칙 — 반드시 지켜라]\n" + BODY;

	/** 규칙 본문(계정 카피 전용) — 근거 수치 인용 지시를 뺀다(클래스 javadoc 참조). */
	public static final String ACCOUNT_BODY = COMMON;

	public static final String ACCOUNT_RULES = "[절제 규칙 — 반드시 지켜라]\n" + ACCOUNT_BODY;

	private LlmGuard() {}
}
