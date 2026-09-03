package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 자체크롤 라우팅 관측 데코레이터 — record는 instagram.source.route 카운터, recordDuration은
 * instagram.source.call 타이머로 남긴다(같은 (path, backend, outcome) 태그, 별개 메트릭 이름 —
 * Micrometer는 같은 이름에 다른 타입을 허용하지 않는다). 지표 기록 실패는 삼킨다(TimedHikerHttp와
 * 같은 원칙).
 */
class MicrometerInstagramSourceMetricsTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final MicrometerInstagramSourceMetrics metrics = new MicrometerInstagramSourceMetrics(registry);

	@Test
	void record는_instagram_source_route_카운터에_태그로_남는다() {
		metrics.record("fetchPost", "self", "ok");

		Counter counter = registry.find("instagram.source.route")
				.tags("path", "fetchPost", "backend", "self", "outcome", "ok").counter();
		assertThat(counter).isNotNull();
		assertThat(counter.count()).isEqualTo(1.0);
	}

	@Test
	void recordDuration은_instagram_source_call_타이머에_같은_태그로_남는다() {
		metrics.recordDuration("fetchPost", "self", "ok", Duration.ofMillis(250).toNanos());

		Timer timer = registry.find("instagram.source.call")
				.tags("path", "fetchPost", "backend", "self", "outcome", "ok").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
		assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isCloseTo(250.0,
				org.assertj.core.data.Offset.offset(1.0));
	}

	@Test
	void record와_recordDuration은_서로_다른_메트릭_이름을_쓴다() {
		// 카운터 instagram.source.route와 타이머 instagram.source.call이 이름이 같으면 Micrometer가
		// 타입 충돌로 예외를 던진다 — 이름이 다르다는 것 자체가 회귀 방지 포인트.
		metrics.record("fetchPost", "self", "ok");
		metrics.recordDuration("fetchPost", "self", "ok", 1_000_000L);

		assertThat(registry.find("instagram.source.route").counter()).isNotNull();
		assertThat(registry.find("instagram.source.call").timer()).isNotNull();
	}

	@Test
	void 지표_기록_실패는_호출자에게_전파되지_않는다() {
		SimpleMeterRegistry dying = new SimpleMeterRegistry() {
			@Override
			protected io.micrometer.core.instrument.Timer newTimer(
					io.micrometer.core.instrument.Meter.Id id,
					io.micrometer.core.instrument.distribution.DistributionStatisticConfig config,
					io.micrometer.core.instrument.distribution.pause.PauseDetector detector) {
				throw new IllegalStateException("레지스트리 죽음 주입");
			}
		};
		MicrometerInstagramSourceMetrics dyingMetrics = new MicrometerInstagramSourceMetrics(dying);

		assertThatCode(() -> dyingMetrics.recordDuration("fetchPost", "self", "ok", 1_000_000L))
				.doesNotThrowAnyException();
	}
}
