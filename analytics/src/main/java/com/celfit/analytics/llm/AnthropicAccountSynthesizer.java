package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.celfit.analytics.config.AnalyticsSettings;

/** 계정 카피 Anthropic 구현 — C1 미러 수치·캡션만 근거로 인플루언서 패널 문구 7종을 생성한다. */
public final class AnthropicAccountSynthesizer implements AccountSynthesisPort {

	private static final String INSTRUCTIONS = """
			당신은 뷰티 브랜드 마케터를 위한 인스타그램 인플루언서 분석가다. 주어진 수치·캡션만
			근거로 삼고 수치를 지어내지 마라. 한국어. 화면에 그대로 노출되는 짧은 문구이므로 분량을 지켜라.

			- tagline: 프로필 헤더 한 줄 소개 — 콘텐츠 성격·톤 (예: "저자극 스킨케어 중심 · 성분과 사용감을 짚는 정보형 리뷰 톤"). 40자 이내
			- summary: 마케터 관점의 계정 분석 요약, 3~4문장
			- trendNote: 최근 흐름 한 문장 (trend_direction·trend_change_pct 근거)
			- chartNote: 게시물별 성과 분포의 특징 한 문장 (예: "잘 터진 3개가 평균을 끌어올림")
			- traits: 콘텐츠 성향 태그 3~5개, 각 2~6자 명사구
			- adHeadline: 광고 비교 수치(organic_avg·ad_avg·ad_drop_pct) 근거 헤드라인 한 문장.
			  입력에 "광고 비교 데이터: 없음"이면 빈 문자열
			- paceNote: 업로드 페이스 한 문장 (avg_interval_days 근거, 예: "주 2~3회 올리는 페이스")
			""";

	private final AnthropicClient client;
	private final AnalyticsSettings settings;

	public AnthropicAccountSynthesizer(AnthropicClient client, AnalyticsSettings settings) {
		this.client = client;
		this.settings = settings;
	}

	@Override
	public AccountCopy synthesize(AccountToAnalyze account) {
		String input = """
				계정: @%s (광고 비교 데이터: %s)
				계정 지표: %s
				카테고리 믹스: %s
				게시물(올린 순, 캡션은 앞부분만): %s
				""".formatted(account.handle(), account.hasAdComparison() ? "있음" : "없음",
				account.summary(), account.categoryStats(), account.posts());
		StructuredMessageCreateParams<AccountCopy> params = MessageCreateParams.builder()
				.model(settings.llmModel())
				.maxTokens(4096L)
				.system(INSTRUCTIONS)
				.outputConfig(AccountCopy.class)
				.addUserMessage(input)
				.build();
		return client.messages().create(params).content().stream()
				.flatMap(block -> block.text().stream())
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("계정 카피 응답에 본문 없음"))
				.text();
	}
}
