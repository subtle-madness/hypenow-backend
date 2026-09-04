package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SurfaceCircuitBreakerTest {

	@Test
	void isTripped는_임계값_도달_전엔_false_도달하면_true_성공하면_다시_false다() {
		SurfaceCircuitBreaker cb = new SurfaceCircuitBreaker(5);
		assertThat(cb.isTripped("embed")).isFalse();

		for (int i = 0; i < 4; i++) {
			cb.recordBlock("embed");
			assertThat(cb.isTripped("embed")).isFalse();
		}
		cb.recordBlock("embed");
		assertThat(cb.isTripped("embed")).isTrue();

		cb.recordSuccess("embed");
		assertThat(cb.isTripped("embed")).isFalse();
	}

	@Test
	void isTripped는_쿨다운_경과_여부와_무관하게_카운터_기준으로만_판단한다() {
		AtomicLong now = new AtomicLong(1_000_000L);
		SurfaceCircuitBreaker cb = new SurfaceCircuitBreaker(5, 60_000L, now::get);
		for (int i = 0; i < 5; i++) {
			cb.recordBlock("embed");
		}
		now.addAndGet(60_000L);
		// 쿨다운은 경과해 isOpen은 false(half-open)지만, isTripped는 카운터 기준이라 여전히 true.
		assertThat(cb.isOpen("embed")).isFalse();
		assertThat(cb.isTripped("embed")).isTrue();
	}

	@Test
	void isTripped는_전역_킬_상태면_아무_표면이나_true다() {
		SurfaceCircuitBreaker cb = new SurfaceCircuitBreaker(5);
		cb.killAll();
		assertThat(cb.isTripped("embed")).isTrue();
		assertThat(cb.isTripped("한번도_안_쓴_표면")).isTrue();
	}

	@Test
	void isTripped_조회는_knownSurfaces에_새_표면을_추가하지_않는다() {
		SurfaceCircuitBreaker cb = new SurfaceCircuitBreaker(5);
		cb.isTripped("한번도_안_쓴_표면");
		assertThat(cb.knownSurfaces()).doesNotContain("한번도_안_쓴_표면");
	}

	@Test
	void knownSurfaces는_recordBlock_recordSuccess로_관측된_표면만_담는다() {
		SurfaceCircuitBreaker cb = new SurfaceCircuitBreaker(5);
		cb.recordBlock("embed");
		cb.recordSuccess("wpi");

		assertThat(cb.knownSurfaces()).containsExactlyInAnyOrder("embed", "wpi");
	}

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

	@Test
	void 쿨다운_경과_전에는_열린_채_유지된다() {
		AtomicLong now = new AtomicLong(1_000_000L);
		SurfaceCircuitBreaker cb = new SurfaceCircuitBreaker(5, 60_000L, now::get);
		for (int i = 0; i < 5; i++) {
			cb.recordBlock("embed");
		}
		assertThat(cb.isOpen("embed")).isTrue();
		now.addAndGet(59_999L);
		assertThat(cb.isOpen("embed")).isTrue();
	}

	@Test
	void 쿨다운_경과_후에는_half_open으로_프로브를_허용한다() {
		AtomicLong now = new AtomicLong(1_000_000L);
		SurfaceCircuitBreaker cb = new SurfaceCircuitBreaker(5, 60_000L, now::get);
		for (int i = 0; i < 5; i++) {
			cb.recordBlock("embed");
		}
		now.addAndGet(60_000L);
		assertThat(cb.isOpen("embed")).isFalse();
	}

	@Test
	void half_open에서_성공하면_완전_리셋된다() {
		AtomicLong now = new AtomicLong(1_000_000L);
		SurfaceCircuitBreaker cb = new SurfaceCircuitBreaker(5, 60_000L, now::get);
		for (int i = 0; i < 5; i++) {
			cb.recordBlock("embed");
		}
		now.addAndGet(60_000L);
		assertThat(cb.isOpen("embed")).isFalse();
		cb.recordSuccess("embed");
		// 시계를 더 옮기지 않아도 스트릭이 0으로 리셋됐으므로 닫힌 상태
		assertThat(cb.isOpen("embed")).isFalse();
		// 다시 4회 블록까지는 닫혀 있어야 한다(스트릭이 진짜 0에서 시작)
		for (int i = 0; i < 4; i++) {
			cb.recordBlock("embed");
			assertThat(cb.isOpen("embed")).isFalse();
		}
	}

	@Test
	void half_open에서_다시_블록되면_트립시각을_갱신하고_다시_연다() {
		AtomicLong now = new AtomicLong(1_000_000L);
		SurfaceCircuitBreaker cb = new SurfaceCircuitBreaker(5, 60_000L, now::get);
		for (int i = 0; i < 5; i++) {
			cb.recordBlock("embed");
		}
		now.addAndGet(60_000L);
		assertThat(cb.isOpen("embed")).isFalse();
		cb.recordBlock("embed");
		// 트립 시각이 현재로 갱신 → 새 쿨다운 시작
		assertThat(cb.isOpen("embed")).isTrue();
		now.addAndGet(59_999L);
		assertThat(cb.isOpen("embed")).isTrue();
		now.addAndGet(1L);
		assertThat(cb.isOpen("embed")).isFalse();
	}
}
