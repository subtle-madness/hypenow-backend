package com.celfit.crawler.crawling.adapter.out.claude;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.AnthropicClient;
import org.junit.jupiter.api.Test;

/**
 * Claude 클라이언트 인증 전환(구독 OAuth 우선 ↔ API 키 폴백) 단위 테스트 —
 * analytics LlmClientFactory와 같은 계약. 클라이언트 생성은 네트워크를 타지 않는다.
 */
class ClaudeClientFactoryTest {

    @Test
    void authToken만_주면_클라이언트_생성에_성공한다() {
        assertDoesNotThrow(() -> ClaudeClientFactory.create("sk-ant-oat01-dummy", null));
    }

    @Test
    void apiKey만_주면_클라이언트_생성에_성공한다() {
        assertDoesNotThrow(() -> ClaudeClientFactory.create(null, "sk-ant-api03-dummy"));
    }

    @Test
    void 둘_다_주면_구독이_우선한다() {
        AnthropicClient client = assertDoesNotThrow(
                () -> ClaudeClientFactory.create("sk-ant-oat01-dummy", "sk-ant-api03-dummy"));
        assertNotNull(client);
        assertEquals(ClaudeClientFactory.MODE_OAUTH,
                ClaudeClientFactory.resolveMode("sk-ant-oat01-dummy", "sk-ant-api03-dummy"));
    }

    @Test
    void 둘_다_없으면_안내_메시지와_함께_실패한다() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ClaudeClientFactory.create(null, null));
        assertTrue(ex.getMessage().contains("ANTHROPIC_AUTH_TOKEN"));
        assertTrue(ex.getMessage().contains("ANTHROPIC_API_KEY"));
    }

    @Test
    void blank_값은_없는_것으로_취급한다() {
        assertThrows(IllegalStateException.class, () -> ClaudeClientFactory.create("  ", ""));
    }
}
