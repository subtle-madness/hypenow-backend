package com.celfit.monitoring.hiker;

import com.celfit.instagram.source.InstagramSourceMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 자체크롤 라우팅 관측 — instagram.source.route 카운터에 태그(path·backend·outcome)로만 남긴다
 * (TimedHikerHttp 관용구: 유한 태그·기록 실패 삼킴). external.call(지연)과 별개 지표라 이중계상 없음.
 */
public class MicrometerInstagramSourceMetrics implements InstagramSourceMetrics {

	private static final Logger log = LoggerFactory.getLogger(MicrometerInstagramSourceMetrics.class);
	static final String METRIC = "instagram.source.route";

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
}
