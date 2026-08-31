package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SurfaceCircuitBreakerTest {

	@Test
	void 연속_5회_블록이면_트립하고_성공하면_리셋한다() {
		SurfaceCircuitBreaker cb = new SurfaceCircuitBreaker(5);
		for (int i = 0; i < 4; i++) {
			cb.recordBlock("embed");
			assertThat(cb.isOpen("embed")).isFalse();
		}
		cb.recordBlock("embed");
		assertThat(cb.isOpen("embed")).isTrue();
		assertThat(cb.isOpen("wpi")).isFalse();
		cb.recordSuccess("embed");
		assertThat(cb.isOpen("embed")).isFalse();
	}

	@Test
	void 전역_킬은_모든_표면을_연다() {
		SurfaceCircuitBreaker cb = new SurfaceCircuitBreaker(5);
		cb.killAll();
		assertThat(cb.isOpen("embed")).isTrue();
		assertThat(cb.isOpen("wpi")).isTrue();
	}
}
