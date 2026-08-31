package com.celfit.monitoring.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.instagram.source.InstagramSource;
import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.InstagramProxyProperties;
import com.celfit.monitoring.hiker.TargetCallContext;
import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.RawPayloadRepository;
import com.celfit.monitoring.store.TargetCallCountRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * Hiker 조립 체인에 외부 콜 타이머가 실제로 끼워지는지 고정(2026-08-23 계층별 p95 뺄셈 설계) —
 * 데코레이터 클래스가 있어도 배선이 빠지면 지표는 영영 안 나온다. 체인 위치(최내곽)의 근거는
 * TimedHikerHttp 주석 참조.
 */
class HikerConfigTest {

	private static final class NoopPayloadRepo extends RawPayloadRepository {
		NoopPayloadRepo() {
			super(null);
		}

		@Override
		public void save(String kind, String subject, int httpStatus, String payloadJson) {
			// DB 없이 통과
		}
	}

	@Test
	void 조립된_InstagramSource_콜은_external_call_타이머에_기록된다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		// selfEnabled=false — 자체 백엔드는 조립만 되고 콜은 전량 Hiker 경로(운영 기본과 동일)
		InstagramProxyProperties proxyProps =
				new InstagramProxyProperties("", "", null, false, false, "", "");
		InstagramSource client = new HikerConfig().instagramSource(path -> "{\"user\":{\"pk\":1}}",
				new NoopPayloadRepo(), new BrandCallContext(), new BrandCallCountRepository(null),
				new TargetCallContext(), new TargetCallCountRepository(null), registry, proxyProps);

		client.fetchProfile("hypenow");

		Timer timer = registry.find("external.call")
				.tags("api", "hiker", "operation", "profile", "outcome", "ok").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}
}
