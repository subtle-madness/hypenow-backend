package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.celfit.analytics.config.AnalyticsSettings;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * LLM 빈 배선. 게이트가 모두 꺼져 있으면 클라이언트를 아예 만들지 않는다 (API 키 불필요).
 * 전 빈 @Lazy라 기동 시 키가 없어도 뜨고, LLM 잡 첫 실행 때 생성된다.
 *
 * <p>프로바이더 선택: app_setting `analytics.llm-provider` — gemini(기본) | vertex(Vertex AI — 07-20 전환)
 * | anthropic(롤백). 포트 빈 생성 시점에 읽으므로 전환은 재기동 필요. ObjectProvider로 반대편 클라이언트는
 * 만들지 않아 gemini 운영 시 ANTHROPIC 키가 없어도 된다(댓글 분류는 MVP 휴면이라 Anthropic 유지 — 미호출이면 무해).
 * vertex는 contentInsightPort/accountSynthesisPort 분기에서 "anthropic이 아님" 경로를 그대로 타 기존
 * Gemini 어댑터를 재사용하고, 주입되는 {@link GeminiApi} 빈만 {@link VertexHttpApi} 구현으로 바뀐다.
 */
@Configuration
@ConditionalOnExpression("${analytics.classify-on-startup:false} or ${analytics.analyze-on-startup:false}"
		+ " or ${analytics.account-analyze-on-startup:false} or ${analytics.admin-enabled:false}")
public class LlmConfig {

	private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

	/**
	 * Vertex를 실제로 쓸지 판정 — provider=vertex이고 SA 키가 존재할 때만. provider=vertex인데
	 * 키가 없으면(크레딧 소진·무설정 로컬·CI) gemini로 폴백하려고 false. 순수 함수라 단위 테스트 대상.
	 */
	static boolean useVertex(String provider, boolean credentialsPresent) {
		return "vertex".equals(provider) && credentialsPresent;
	}

	@Bean
	@Lazy
	public AnthropicClient anthropicClient() {
		return LlmClientFactory.fromEnv(); // ANTHROPIC_AUTH_TOKEN(구독) 우선, 없으면 ANTHROPIC_API_KEY
	}

	@Bean
	@Lazy
	public CommentClassificationPort commentClassificationPort(AnthropicClient client, AnalyticsSettings settings) {
		return new AnthropicCommentClassifier(client, settings);
	}

	@Bean
	@Lazy
	public BeautyTaxonomyLoader beautyTaxonomyLoader(
			@Qualifier("analysisDataSource") DataSource analysisDataSource) {
		return new BeautyTaxonomyLoader(analysisDataSource);
	}

	@Bean
	@Lazy
	public GeminiApi geminiApi(AnalyticsSettings settings) {
		String provider = settings.llmProvider();
		if (useVertex(provider, VertexTokenProvider.credentialsPresent())) {
			return VertexHttpApi.fromEnv(settings); // SA 토큰 + app_setting 프로젝트/버킷
		}
		if ("vertex".equals(provider)) {
			// baseline은 vertex지만 SA 키가 없다 — 죽이지 않고 무료 gemini로 폴백(크레딧 소진·무설정 로컬 안전).
			log.warn("provider=vertex인데 GOOGLE_APPLICATION_CREDENTIALS 미설정 — gemini로 폴백");
		}
		return GeminiHttpApi.fromEnv(settings.geminiRpm()); // GEMINI_API_KEY (무료 프로젝트)
	}

	@Bean
	@Lazy
	public ContentInsightPort contentInsightPort(AnalyticsSettings settings,
			ObjectProvider<AnthropicClient> anthropic, ObjectProvider<GeminiApi> gemini,
			BeautyTaxonomyLoader taxonomyLoader) {
		if ("anthropic".equals(settings.llmProvider())) {
			AnthropicClient client = anthropic.getObject();
			return new AnthropicContentInsight(
					new AnthropicContentAttributeAnalyzer(client, settings, taxonomyLoader),
					new AnthropicSynthesizer(client, settings));
		}
		return new GeminiContentAnalyzer(gemini.getObject(), settings::geminiModel, taxonomyLoader::get);
	}

	@Bean
	@Lazy
	public TraitTaxonomyLoader traitTaxonomyLoader(
			@Qualifier("analysisDataSource") DataSource analysisDataSource) {
		return new TraitTaxonomyLoader(analysisDataSource);
	}

	@Bean
	@Lazy
	public AccountSynthesisPort accountSynthesisPort(AnalyticsSettings settings,
			ObjectProvider<AnthropicClient> anthropic, ObjectProvider<GeminiApi> gemini,
			TraitTaxonomyLoader traitLoader) {
		if ("anthropic".equals(settings.llmProvider())) {
			return new AnthropicAccountSynthesizer(anthropic.getObject(), settings, traitLoader);
		}
		return new GeminiAccountSynthesizer(gemini.getObject(), settings::geminiModel, traitLoader::get);
	}

	/** trait 배치 매핑(원샷 이행) — ContentSynthesisPort와 같은 이유로 Gemini/Vertex만 지원. */
	@Bean
	@Lazy
	public TraitMappingPort traitMappingPort(AnalyticsSettings settings,
			ObjectProvider<GeminiApi> gemini, TraitTaxonomyLoader traitLoader) {
		return new GeminiTraitMapper(gemini.getObject(), settings::geminiModel, traitLoader::get);
	}

	/**
	 * 해석 문구 전용 포트 — 갱신 잡이 쓴다. Anthropic 경로는 아직 없어 Gemini/Vertex만 지원한다
	 * (갱신은 일회성 배치라 프로바이더 폴백까지 둘 필요가 없다).
	 */
	@Bean
	@Lazy
	public ContentSynthesisPort contentSynthesisPort(AnalyticsSettings settings,
			ObjectProvider<GeminiApi> gemini) {
		return new GeminiContentSynthesizer(gemini.getObject(), settings::geminiModel);
	}

	/**
	 * 공동구매(공구) 애매분 판정 포트 — {@link ContentSynthesisPort}와 같은 이유로 Gemini/Vertex
	 * 전용이다(콜 규모가 백로그 수백 건 1회 + 일 한두 건이라 프로바이더 폴백까지 둘 필요가 없다,
	 * 스펙 §3).
	 *
	 * <p>provider=anthropic(롤백 경로)이면 {@link GeminiApi} 빈을 아예 조회하지 않는다 —
	 * {@code gemini.getObject()}는 @Lazy 빈이라도 호출 즉시 생성을 강제해, GEMINI_API_KEY 없이
	 * anthropic만으로 운영 중인 환경에서 첫 GROUP_PURCHASE_JUDGE 트리거가 {@code
	 * GeminiHttpApi.fromEnv()}의 키 부재 예외로 죽는다({@code com.celfit.analytics.config.JobConfig#batchApiOrNull}과 같은
	 * 방어). 대신 호출 시 즉시 실패하는 포트를 반환해 잡이 게시물 단위로 verdict NULL 격리 처리하고
	 * 연속 실패 서킷브레이커로 빠르게 멈추게 한다.
	 */
	@Bean
	@Lazy
	public com.celfit.analytics.grouppurchase.GroupPurchaseJudgePort groupPurchaseJudgePort(
			AnalyticsSettings settings, ObjectProvider<GeminiApi> gemini) {
		if ("anthropic".equals(settings.llmProvider())) {
			return caption -> {
				throw new IllegalStateException(
						"공동구매 판정은 gemini/vertex 프로바이더에서만 지원합니다 (provider=anthropic)");
			};
		}
		return new com.celfit.analytics.grouppurchase.GeminiGroupPurchaseJudge(
				gemini.getObject(), settings::geminiModel);
	}
}
