package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 클라이언트 인증 전환 — 구독(OAuth 토큰) 우선, API 키 폴백.
 *
 * <p>전환 방법:
 * <ul>
 *   <li><b>구독(Claude 구독 OAuth)</b>: {@code ant auth print-credentials --access-token}으로 단기
 *       토큰을 발급해 환경변수 {@code ANTHROPIC_AUTH_TOKEN}에 넣는다. 토큰은 단기 만료라 실행 직전 발급.</li>
 *   <li><b>자동화(운영)</b>: 환경변수 {@code ANTHROPIC_API_KEY}.</li>
 * </ul>
 * 둘 다 설정돼 있으면 구독이 우선한다 — 코드가 authToken만 SDK에 넘기고 apiKey는 무시하므로
 * SDK가 두 자격증명을 동시에 헤더에 실어 API가 요청을 거부하는 문제가 생기지 않는다.
 */
public final class LlmClientFactory {

	private static final Logger log = LoggerFactory.getLogger(LlmClientFactory.class);

	/** OAuth 토큰은 Bearer 인증에 이 베타 헤더가 필요하다(SDK가 자동으로 붙여주지 않음). */
	private static final String OAUTH_BETA_HEADER = "anthropic-beta";
	private static final String OAUTH_BETA_VALUE = "oauth-2025-04-20";

	static final String MODE_OAUTH = "구독(OAuth)";
	static final String MODE_API_KEY = "API 키";

	private LlmClientFactory() {
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
				"LLM 자격증명이 없습니다. ANTHROPIC_AUTH_TOKEN(구독, ant auth print-credentials --access-token) "
						+ "또는 ANTHROPIC_API_KEY(자동화) 중 하나를 설정하세요.");
	}

	/** 환경변수에서 읽어 생성. 어떤 모드가 선택됐는지 info 로그로 남긴다. */
	public static AnthropicClient fromEnv() {
		String authToken = System.getenv("ANTHROPIC_AUTH_TOKEN");
		String apiKey = System.getenv("ANTHROPIC_API_KEY");
		String mode = resolveMode(authToken, apiKey);
		log.info("LLM 인증 모드: {}", mode);
		return create(authToken, apiKey);
	}
}
