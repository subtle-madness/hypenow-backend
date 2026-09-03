package com.celfit.monitoring.hiker;

import com.celfit.instagram.source.InstagramSourceMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 자체크롤 라우팅 관측 — instagram.source.route 카운터에 태그(path·backend·outcome)로만 남긴다
 * (TimedHikerHttp 관용구: 유한 태그·기록 실패 삼킴). external.call(지연)과 별개 지표라 이중계상 없음.
 *
 * <p>소요시간은 별도 타이머 instagram.source.call로 남긴다(카운터 instagram.source.route와 이름을
 * 다르게 둔다 — Micrometer는 같은 이름에 다른 메트릭 타입을 허용하지 않는다). 자체크롤(self) 경로가
 * Hiker HTTP의 external.call 타이머(TimedHikerHttp)를 우회해서 생기던 지연 관측 공백을 메운다.
 */
public class MicrometerInstagramSourceMetrics implements InstagramSourceMetrics {

	private static final Logger log = LoggerFactory.getLogger(MicrometerInstagramSourceMetrics.class);
	static final String METRIC = "instagram.source.route";
	static final String DURATION_METRIC = "instagram.source.call";

	private final MeterRegistry registry;

	public MicrometerInstagramSourceMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void record(String path, String backend, String outcome) {
		try {
			Counter.builder(METRIC)
					.tag("path", path).tag("backend", backend).tag("outcome", outcome)
					.register(registry)
					.increment();
		} catch (RuntimeException e) {
			log.warn("자체크롤 라우팅 지표 기록 실패(무시) — {} {} {}: {}", path, backend, outcome, e.toString());
		}
	}

	@Override
	public void recordDuration(String path, String backend, String outcome, long elapsedNanos) {
		try {
			Timer.builder(DURATION_METRIC)
					.tag("path", path).tag("backend", backend).tag("outcome", outcome)
					.register(registry)
					.record(Duration.ofNanos(elapsedNanos));
		} catch (RuntimeException e) {
			// 관측이 수집을 죽이면 안 된다(TimedHikerHttp·record와 같은 원칙) — 기록 실패는 로그만.
			log.warn("자체크롤 라우팅 소요시간 기록 실패(무시) — {} {} {}: {}", path, backend, outcome, e.toString());
		}
	}
}
