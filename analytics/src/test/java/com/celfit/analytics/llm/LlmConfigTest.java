package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.grouppurchase.GroupPurchaseJudgePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** provider=vertex 그레이스풀 폴백 판정(useVertex) 회귀 고정. */
class LlmConfigTest {

	@Test
	void vertex는_프로바이더가_vertex이고_SA키가_있을_때만_사용() {
		assertTrue(LlmConfig.useVertex("vertex", true));
	}

	@Test
	void provider가_vertex라도_SA키가_없으면_gemini로_폴백() {
		assertFalse(LlmConfig.useVertex("vertex", false));
	}

	@Test
	void provider가_vertex가_아니면_SA키_유무와_무관하게_vertex_미사용() {
		assertFalse(LlmConfig.useVertex("gemini", true));
		assertFalse(LlmConfig.useVertex("anthropic", true));
	}

	/**
	 * 리뷰 발견 — provider=anthropic 롤백 상태에서 GROUP_PURCHASE_JUDGE가 트리거되면
	 * groupPurchaseJudgePort 빈이 gemini.getObject()를 강제 호출해(=Lazy 빈 생성) GEMINI_API_KEY
	 * 없는 환경에서 GeminiHttpApi.fromEnv()가 던지며 잡 자체가 죽었다. gemini 빈을 아예 만들지
	 * 않고, judge() 호출 시점에만 명확한 한국어 예외를 던지는 포트로 격리했는지 고정한다.
	 */
	@Test
	void anthropic_프로바이더면_gemini_빈을_만들지_않고_judge_호출_시_예외를_던진다() {
		AnalyticsSettings anthropicSettings = new AnalyticsSettings(null) {
			@Override
			public String llmProvider() {
				return "anthropic";
			}
		};
		ObjectProvider<GeminiApi> gemini = new ObjectProvider<>() {
			@Override
			public GeminiApi getObject() {
				throw new AssertionError("gemini 빈 생성이 호출되면 안 된다(provider=anthropic)");
			}
		};

		GroupPurchaseJudgePort port = new LlmConfig().groupPurchaseJudgePort(anthropicSettings, gemini);

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> port.judge("공구 없이 조립"));
		assertTrue(e.getMessage().contains("gemini/vertex"), e.getMessage());
	}
}
