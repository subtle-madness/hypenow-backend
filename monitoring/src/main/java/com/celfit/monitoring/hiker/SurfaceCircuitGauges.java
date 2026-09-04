package com.celfit.monitoring.hiker;

import com.celfit.instagram.source.self.SurfaceCircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 자체크롤 표면별 서킷 트립 상태를 게이지로 노출 — instagram.source.self.circuit.open{surface, source}
 * (1=트립, 0=닫힘). SelfCrawlBackend.run()이 실제로 쓰는 표면 5종(embed/comment/wpi/og/feed)에
 * 고정 등록한다 — SurfaceCircuitBreaker.knownSurfaces()로 동적 등록하면 트래픽이 아예 없던 표면은
 * 등록조차 안 돼(0으로도 안 뜸) "닫혀 있다"와 "관측 자체가 없다"가 구분이 안 된다. 운영에서 09-04
 * self 서킷 트립·복구가 로그·게이지 둘 다 없어 관측이 비어 있던 공백을 메운다.
 */
public final class SurfaceCircuitGauges {

	private static final Logger log = LoggerFactory.getLogger(SurfaceCircuitGauges.class);

	/** SelfCrawlBackend.run(surface, ...) 호출부(56~80행)와 정확히 일치해야 한다. */
	static final List<String> SURFACES = List.of("embed", "comment", "wpi", "og", "feed");

	private SurfaceCircuitGauges() {
	}

	/** source는 어느 SurfaceCircuitBreaker 인스턴스인지 구분하는 태그(예: "batch"·"sync"
	 * — HikerConfig의 instagramSource/syncInstagramSource가 각자 별도 인스턴스를 쓴다). */
	public static void register(MeterRegistry registry, SurfaceCircuitBreaker breaker, String source) {
		for (String surface : SURFACES) {
			try {
				Gauge.builder("instagram.source.self.circuit.open", breaker,
								b -> b.isTripped(surface) ? 1 : 0)
						.tag("surface", surface)
						.tag("source", source)
						.strongReference(true)
						.register(registry);
			} catch (RuntimeException e) {
				log.warn("자체크롤 서킷 게이지 등록 실패(무시) — surface={} source={}: {}", surface, source,
						e.toString());
			}
		}
	}
}
