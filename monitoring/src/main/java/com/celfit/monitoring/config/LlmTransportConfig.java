package com.celfit.monitoring.config;

import com.celfit.common.llm.VertexHttpTransport;
import com.celfit.common.llm.VertexTokenProvider;
import com.celfit.monitoring.llm.GeminiHttp;
import com.celfit.monitoring.llm.GeminiHttpTransport;
import com.celfit.monitoring.llm.VertexGeminiHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gemini 호출 전송 배선(2026-08-18 Vertex 전환) — {@code BrandMentionJudge}·
 * {@code AdDisclosureExtractorGemini}가 공유하는 {@link GeminiHttp} 빈을 여기서 조립한다.
 * SA 키(GOOGLE_APPLICATION_CREDENTIALS)와 Vertex 프로젝트가 모두 설정돼 있으면 Vertex로,
 * 아니면 기존 AI Studio(GEMINI_API_KEY)로 그레이스풀 폴백한다(analytics
 * {@code LlmConfig.useVertex} 패턴 준용).
 *
 * <p>계기: #490(백필 기동 즉시·상한 제거) 이후 스테이징에서 무료 키 쿼터를 운영과 공유하는
 * 구조 탓에 429가 폭주(2026-08-18 실측 — 15분간 분당 83~146건). Vertex는 프로젝트 전용
 * 쿼터(DSQ)라 이 공유 문제가 없다(DECISIONS.md 08-18 항목 참조).
 */
@Configuration
public class LlmTransportConfig {

	private static final Logger log = LoggerFactory.getLogger(LlmTransportConfig.class);

	/**
	 * provider=vertex 그레이스풀 폴백 판정 — SA 키와 프로젝트 ID가 모두 있을 때만 Vertex를 쓴다
	 * (analytics {@code LlmConfig.useVertex}와 동형, 다만 monitoring엔 명시적 provider 토글
	 * app_setting이 없어 "프로젝트 설정 여부"로 대신 판정한다 — 설계 판단, 08-18). 순수 함수라
	 * 단위 테스트 대상.
	 */
	static boolean useVertex(String vertexProject, boolean credentialsPresent) {
		return credentialsPresent && vertexProject != null && !vertexProject.isBlank();
	}

	@Bean
	public GeminiHttp geminiHttp(
			@Value("${monitoring.llm.vertex-project:}") String vertexProject,
			@Value("${monitoring.llm.vertex-location:global}") String vertexLocation,
			@Value("${monitoring.gemini.api-key:}") String apiKey,
			@Value("${monitoring.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl) {
		if (useVertex(vertexProject, VertexTokenProvider.credentialsPresent())) {
			VertexHttpTransport transport = new VertexHttpTransport(
					VertexTokenProvider.fromEnv(), VertexHttpTransport.DEFAULT_BASE_URL, 15_000);
			log.info("Gemini 전송 — Vertex 사용 (project={}, location={})", vertexProject, vertexLocation);
			return new VertexGeminiHttp(transport, vertexProject, vertexLocation);
		}
		if (vertexProject != null && !vertexProject.isBlank()) {
			// baseline은 vertex지만 SA 키가 없다 — 죽이지 않고 AI Studio로 폴백(크레딧 소진·무설정 로컬 안전)
			log.warn("monitoring.llm.vertex-project 설정됐지만 GOOGLE_APPLICATION_CREDENTIALS 미설정 —"
					+ " AI Studio(GEMINI_API_KEY)로 폴백");
		}
		return new GeminiHttpTransport(apiKey, baseUrl);
	}

	/**
	 * BrandMentionJudge·AdDisclosureExtractorGemini의 "호출 가능 상태" 플래그 — Vertex 사용 시
	 * GEMINI_API_KEY가 비어 있어도 활성이어야 하므로, apiKey 문자열이 아니라 이 값을 넘긴다.
	 */
	@Bean
	public LlmEnabled llmEnabled(
			@Value("${monitoring.llm.vertex-project:}") String vertexProject,
			@Value("${monitoring.gemini.api-key:}") String apiKey) {
		boolean enabled = useVertex(vertexProject, VertexTokenProvider.credentialsPresent())
				|| (apiKey != null && !apiKey.isBlank());
		return new LlmEnabled(enabled);
	}

	/** 단순 boolean 대신 전용 타입을 쓰는 이유 — 다른 무관한 boolean 빈과의 Spring 자동배선 충돌 방지. */
	public record LlmEnabled(boolean value) {
	}
}
