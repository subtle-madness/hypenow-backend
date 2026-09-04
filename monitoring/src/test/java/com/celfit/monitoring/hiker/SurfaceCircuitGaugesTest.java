package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.instagram.source.self.SurfaceCircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** 자체크롤 서킷 트립 상태 게이지 — 트립 전 0, 임계값 도달 후 1로 바뀌는지만 검증(값 매핑이 핵심). */
class SurfaceCircuitGaugesTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private Gauge gauge(String surface, String source) {
		return registry.find("instagram.source.self.circuit.open")
				.tags("surface", surface, "source", source).gauge();
	}

	@Test
	void 트립_전에는_0이고_임계값_도달하면_1로_바뀐다() {
		SurfaceCircuitBreaker circuit = new SurfaceCircuitBreaker(5);
		SurfaceCircuitGauges.register(registry, circuit, "batch");

		Gauge embedGauge = gauge("embed", "batch");
		assertThat(embedGauge).isNotNull();
		assertThat(embedGauge.value()).isZero();

		for (int i = 0; i < 5; i++) {
			circuit.recordBlock("embed");
		}

		assertThat(embedGauge.value()).isEqualTo(1.0);

		circuit.recordSuccess("embed");
		assertThat(embedGauge.value()).isZero();
	}

	@Test
	void 표면_5종_source_태그로_전부_등록된다() {
		SurfaceCircuitBreaker circuit = new SurfaceCircuitBreaker(5);
		SurfaceCircuitGauges.register(registry, circuit, "sync");

		for (String surface : new String[] {"embed", "comment", "wpi", "og", "feed"}) {
			assertThat(gauge(surface, "sync")).as("surface=%s", surface).isNotNull();
		}
	}
}
