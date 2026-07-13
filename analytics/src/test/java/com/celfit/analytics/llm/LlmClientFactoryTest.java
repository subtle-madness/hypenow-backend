package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.AnthropicClient;
import org.junit.jupiter.api.Test;

/**
 * LLM 클라이언트 인증 전환(구독 OAuth ↔ API 키) 단위 테스트.
 * 클라이언트 생성은 네트워크를 타지 않으므로 실제 API 호출 없이 검증 가능.
 */
class LlmClientFactoryTest {

	@Test
	void authToken만_주면_클라이언트_생성에_성공한다() {
		assertDoesNotThrow(() -> LlmClientFactory.create("sk-ant-oat01-dummy", null));
	}

	@Test
	void apiKey만_주면_클라이언트_생성에_성공한다() {
		assertDoesNotThrow(() -> LlmClientFactory.create(null, "sk-ant-api03-dummy"));
	}

	@Test
	void 둘_다_주면_authToken이_우선한다() {
		AnthropicClient client =
				assertDoesNotThrow(() -> LlmClientFactory.create("sk-ant-oat01-dummy", "sk-ant-api03-dummy"));
		assertTrue(client != null);
		assertTrue(LlmClientFactory.MODE_OAUTH.equals(LlmClientFactory.resolveMode("sk-ant-oat01-dummy", "sk-ant-api03-dummy")));
	}

	@Test
	void 둘_다_없으면_안내_메시지와_함께_실패한다() {
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> LlmClientFactory.create(null, null));

		assertTrue(ex.getMessage().contains("ANTHROPIC_AUTH_TOKEN"));
		assertTrue(ex.getMessage().contains("ANTHROPIC_API_KEY"));
	}

	@Test
	void blank_값은_없는_것으로_취급한다() {
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> LlmClientFactory.create("  ", ""));

		assertTrue(ex.getMessage().contains("ANTHROPIC_AUTH_TOKEN"));
		assertTrue(ex.getMessage().contains("ANTHROPIC_API_KEY"));
	}
}
