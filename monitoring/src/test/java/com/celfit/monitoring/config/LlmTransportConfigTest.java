package com.celfit.monitoring.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.monitoring.llm.GeminiHttp;
import com.celfit.monitoring.llm.TimedGeminiHttp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * provider=vertex 그레이스풀 폴백 판정(useVertex) 회귀 고정 — analytics {@code LlmConfigTest}와
 * 동형(08-18 Vertex 전환). monitoring엔 명시적 provider 토글이 없어 "프로젝트 설정 여부"로
 * 대신 판정한다(설계 판단, PR 보고 참조).
 */
class LlmTransportConfigTest {

	@Test
	void vertex는_프로젝트가_설정되고_SA_키가_있을_때만_사용() {
		assertTrue(LlmTransportConfig.useVertex("hypenow-llm-prod", true));
	}

	@Test
	void 프로젝트가_설정돼도_SA_키가_없으면_AI_Studio로_폴백() {
		assertFalse(LlmTransportConfig.useVertex("hypenow-llm-prod", false));
	}

	@Test
	void SA_키가_있어도_프로젝트_미설정이면_vertex_미사용() {
		assertFalse(LlmTransportConfig.useVertex("", true));
		assertFalse(LlmTransportConfig.useVertex(null, true));
		assertFalse(LlmTransportConfig.useVertex("  ", true));
	}

	@Test
	void 프로젝트도_SA_키도_없으면_vertex_미사용() {
		assertFalse(LlmTransportConfig.useVertex("", false));
		assertFalse(LlmTransportConfig.useVertex(null, false));
	}

	/** 외부 콜 타이머 배선 고정(2026-08-23) — 데코레이터가 있어도 빈 조립에서 빠지면 지표는 안 나온다. */
	@Test
	void geminiHttp_빈은_타이머_데코레이터로_감싸진다() {
		GeminiHttp bean = new LlmTransportConfig().geminiHttp(
				"", "global", "test-key", "http://127.0.0.1:1", new SimpleMeterRegistry());

		assertInstanceOf(TimedGeminiHttp.class, bean);
	}
}
