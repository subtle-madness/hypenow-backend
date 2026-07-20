package com.celfit.analytics.llm;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.FileInputStream;
import java.util.List;
import java.util.function.Supplier;

/**
 * Vertex AI 인증 — 서비스 계정 JSON(GOOGLE_APPLICATION_CREDENTIALS 경로) → cloud-platform
 * 스코프 액세스 토큰. 만료 자동 갱신(라이브러리가 5분 여유로 판단). 실키 필요라 검증은 스모크.
 *
 * <p>순수 JDK HTTP 컨벤션의 예외로 google-auth 라이브러리를 쓴다 — SA JWT 서명·토큰 교환은
 * 보안 민감 영역이라 자체 구현(RS256 수제 서명)의 버그 리스크가 전이 의존성 무게보다 크다고 판단.
 */
public final class VertexTokenProvider implements Supplier<String> {

	private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

	private final GoogleCredentials credentials;

	VertexTokenProvider(GoogleCredentials credentials) {
		this.credentials = credentials;
	}

	public static VertexTokenProvider fromEnv() {
		String path = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
		if (path == null || path.isBlank()) {
			throw new IllegalStateException(
					"GOOGLE_APPLICATION_CREDENTIALS 미설정 — SA 키 경로 셸 export 필요 (.env는 JVM에 자동 로드되지 않음)");
		}
		try (FileInputStream in = new FileInputStream(path)) {
			return new VertexTokenProvider(GoogleCredentials.fromStream(in).createScoped(List.of(SCOPE)));
		} catch (java.io.IOException e) {
			throw new IllegalStateException("SA 키 로드 실패: " + path, e);
		}
	}

	@Override
	public synchronized String get() {
		try {
			credentials.refreshIfExpired();
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Vertex 액세스 토큰 갱신 실패", e);
		}
		return credentials.getAccessToken().getTokenValue();
	}
}
