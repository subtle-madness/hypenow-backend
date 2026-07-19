package com.celfit.crawler.crawling.adapter.out.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claude 클라이언트 인증 전환 — 구독(OAuth 토큰) 우선, API 키 폴백.
 * analytics LlmClientFactory와 같은 계약(모듈 간 import 금지라 crawler에 복제 — §4-4).
 *
 * <p>구독 토큰은 로컬 맥에서 {@code claude setup-token}으로 발급한 장기 토큰(sk-ant-oat01-…)을
 * 서버 환경변수 {@code ANTHROPIC_AUTH_TOKEN}에 넣는다. authToken이 있으면 apiKey는 SDK에
 * 아예 넘기지 않는다 — 이중 자격증명 거부와 API 과금 유출을 동시에 차단.
 */
final class ClaudeClientFactory {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClientFactory.class);

    /** OAuth 토큰은 Bearer 인증에 이 베타 헤더가 필요하다(SDK가 자동으로 붙여주지 않음). */
    private static final String OAUTH_BETA_HEADER = "anthropic-beta";
    private static final String OAUTH_BETA_VALUE = "oauth-2025-04-20";

    static final String MODE_OAUTH = "구독(OAuth)";
    static final String MODE_API_KEY = "API 키";

    private ClaudeClientFactory() {
    }

    /** 테스트 가능한 코어: authToken 우선(구독, Bearer+oauth beta 헤더), 폴백 apiKey. */
    static AnthropicClient create(String authToken, String apiKey) {
        String mode = resolveMode(authToken, apiKey);
        if (MODE_OAUTH.equals(mode)) {
            return AnthropicOkHttpClient.builder()
                    .authToken(authToken)
                    .putHeader(OAUTH_BETA_HEADER, OAUTH_BETA_VALUE)
                    .build();
        }
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }

    /** authToken 우선, 폴백 apiKey, 둘 다 없으면 안내 메시지와 함께 실패. */
    static String resolveMode(String authToken, String apiKey) {
        if (authToken != null && !authToken.isBlank()) {
            return MODE_OAUTH;
        }
        if (apiKey != null && !apiKey.isBlank()) {
            return MODE_API_KEY;
        }
        throw new IllegalStateException(
                "Claude 자격증명이 없습니다. ANTHROPIC_AUTH_TOKEN(구독, claude setup-token으로 발급) "
                        + "또는 ANTHROPIC_API_KEY(API 과금) 중 하나를 설정하세요.");
    }

    /** 환경변수에서 읽어 생성. 어떤 모드가 선택됐는지 info 로그로 남긴다. */
    static AnthropicClient fromEnv() {
        String authToken = System.getenv("ANTHROPIC_AUTH_TOKEN");
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        String mode = resolveMode(authToken, apiKey);
        log.info("Claude 판정 인증 모드: {}", mode);
        return create(authToken, apiKey);
    }
}
