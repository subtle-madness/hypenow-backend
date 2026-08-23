package com.celfit.monitoring.llm;

import com.celfit.common.llm.LlmQuotaExhaustedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gemini 외부 콜 타이머 데코레이터(2026-08-23 대시보드 진단 설계) — Hiker
 * ({@code TimedHikerHttp})와 같은 {@code external.call} 타이머에 api=gemini 태그로 남긴다.
 * operation은 경로의 액션 세그먼트("models/{m}:{action}"의 action — 현재 generateContent
 * 1종)라 카디널리티가 코드 통제 범위다. 재시도·백오프는 delegate(AI Studio·Vertex 전송)
 * 내부에 있어 기록은 논리 콜 1건의 총 소요다(재시도로 살아난 콜은 ok).
 *
 * <p>outcome 분류: 전송 계층 예외가 상태코드를 타입으로 안 나르므로(IllegalStateException 메시지
 * 문자열뿐 — 파싱은 취약해 하지 않는다) 유일한 타입 신호인 쿼터 소진(429)만 4xx로, 나머지는
 * error로 뭉친다. 관찰 전용: 바디·예외는 그대로 통과, 지표 기록 실패는 삼킨다.
 */
public final class TimedGeminiHttp implements GeminiHttp {

	private static final Logger log = LoggerFactory.getLogger(TimedGeminiHttp.class);

	private final GeminiHttp delegate;
	private final MeterRegistry registry;

	public TimedGeminiHttp(GeminiHttp delegate, MeterRegistry registry) {
		this.delegate = delegate;
		this.registry = registry;
	}

	@Override
	public String post(String path, String jsonBody) {
		long start = System.nanoTime();
		String outcome = "error";
		try {
			String body = delegate.post(path, jsonBody);
			outcome = "ok";
			return body;
		} catch (LlmQuotaExhaustedException e) {
			outcome = "4xx";
			throw e;
		} finally {
			record(operationOf(path), outcome, System.nanoTime() - start);
		}
	}

	/** "models/{m}:{action}" 액션 세그먼트 — 없으면 other(모델명은 태그에 싣지 않는다 — 카디널리티 통제). */
	static String operationOf(String path) {
		int colon = path.lastIndexOf(':');
		return colon >= 0 && colon < path.length() - 1 ? path.substring(colon + 1) : "other";
	}

	private void record(String operation, String outcome, long elapsedNanos) {
		try {
			Timer.builder("external.call")
					.tag("api", "gemini").tag("operation", operation).tag("outcome", outcome)
					.register(registry)
					.record(Duration.ofNanos(elapsedNanos));
		} catch (RuntimeException e) {
			// 관측이 판정을 죽이면 안 된다 — 기록 실패는 로그만
			log.warn("외부 콜 지표 기록 실패(무시) — {} {}: {}", operation, outcome, e.toString());
		}
	}
}
