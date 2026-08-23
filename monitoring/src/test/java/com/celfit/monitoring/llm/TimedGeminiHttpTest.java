package com.celfit.monitoring.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.common.llm.LlmQuotaExhaustedException;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * Gemini 외부 콜 타이머 데코레이터(2026-08-23 계층별 p95 뺄셈 설계) — Hiker와 같은
 * external.call 타이머에 api=gemini로 남는다. 재시도는 delegate(GeminiHttpTransport·
 * VertexHttpTransport) 내부라 여기 기록은 논리 콜 1건의 총 소요다.
 */
class TimedGeminiHttpTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private Timer timer(String operation, String outcome) {
		return registry.find("external.call")
				.tags("api", "gemini", "operation", operation, "outcome", outcome).timer();
	}

	@Test
	void 성공_콜은_바디를_그대로_돌려주고_outcome_ok로_기록한다() {
		TimedGeminiHttp timed = new TimedGeminiHttp((path, body) -> "{\"candidates\":[]}", registry);

		String body = timed.post("/v1beta/models/gemini-2.5-flash:generateContent", "{}");

		assertThat(body).isEqualTo("{\"candidates\":[]}");
		Timer timer = timer("generateContent", "ok");
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	void 쿼터_소진은_outcome_4xx로_기록하고_예외를_그대로_전파한다() {
		TimedGeminiHttp timed = new TimedGeminiHttp((path, body) -> {
			throw new LlmQuotaExhaustedException("Vertex 429 재시도 소진");
		}, registry);

		assertThatThrownBy(() -> timed.post("/v1beta/models/gemini-2.5-flash:generateContent", "{}"))
				.isInstanceOf(LlmQuotaExhaustedException.class);
		assertThat(timer("generateContent", "4xx").count()).isEqualTo(1);
	}

	@Test
	void 기타_실패는_outcome_error로_기록한다() {
		TimedGeminiHttp timed = new TimedGeminiHttp((path, body) -> {
			throw new IllegalStateException("Gemini HTTP 500: boom");
		}, registry);

		assertThatThrownBy(() -> timed.post("/v1beta/models/gemini-2.5-flash:generateContent", "{}"))
				.isInstanceOf(IllegalStateException.class);
		assertThat(timer("generateContent", "error").count()).isEqualTo(1);
	}

	@Test
	void 액션_세그먼트가_없는_경로는_operation_other로_기록한다() {
		TimedGeminiHttp timed = new TimedGeminiHttp((path, body) -> "{}", registry);

		timed.post("/v1beta/weird/path", "{}");

		assertThat(timer("other", "ok").count()).isEqualTo(1);
	}
}
