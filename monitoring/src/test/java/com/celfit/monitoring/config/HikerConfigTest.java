package com.celfit.monitoring.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.instagram.source.InstagramSource;
import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.IgSourceSettings;
import com.celfit.monitoring.hiker.InstagramProxyProperties;
import com.celfit.monitoring.hiker.TargetCallContext;
import com.celfit.monitoring.store.AppSettingRepository;
import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.RawPayloadRepository;
import com.celfit.monitoring.store.TargetCallCountRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
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

	/** DB 없는 설정 스텁 — 키 전부 부재 → 안전측 기본(self off, 전량 Hiker). */
	private static final class EmptySettingsRepo extends AppSettingRepository {
		EmptySettingsRepo() {
			super(null);
		}

		@Override
		public Optional<String> find(String key) {
			return Optional.empty();
		}
	}

	@Test
	void 조립된_InstagramSource_콜은_external_call_타이머에_기록된다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		// 자체크롤 토글은 app_setting(기본 off) — 자체 백엔드는 조립만 되고 콜은 전량 Hiker 경로(운영 기본과 동일)
		InstagramProxyProperties proxyProps =
				new InstagramProxyProperties("", "", null, false, "", "");
		InstagramSource client = new HikerConfig().instagramSource(path -> "{\"user\":{\"pk\":1}}",
				new NoopPayloadRepo(), new BrandCallContext(), new BrandCallCountRepository(null),
				new TargetCallContext(), new TargetCallCountRepository(null), registry, proxyProps,
				new IgSourceSettings(new EmptySettingsRepo(), proxyProps), Duration.ofSeconds(8));

		client.fetchProfile("hypenow");

		Timer timer = registry.find("external.call")
				.tags("api", "hiker", "operation", "profile", "outcome", "ok").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);

		// 라우팅 관측 배선도 함께 고정 — 새 카운터는 external.call과 별개 지표(이중계상 아님)
		Counter route = registry.find("instagram.source.route")
				.tags("path", "fetchProfile", "backend", "hiker", "outcome", "ok").counter();
		assertThat(route).isNotNull();
		assertThat(route.count()).isEqualTo(1.0);
	}

	/**
	 * 사용자 대면 동기 경로 전용 빈(syncInstagramSource, HikerFirstInstagramSource) 조립 고정 —
	 * self-enabled=true여도(EmptySettingsRepo는 안전측 false를 주므로 여기서는 프록시 미설정으로
	 * self가 구조적으로 비활성인 상태) Hiker 성공 경로는 그대로 동작하고 external.call·
	 * instagram.source.route 지표 배선도 동일하게 남는다 — 정책(Hiker 1순위+장애시 self 구조)
	 * 자체의 세부 분기(구조 성공·실패)는 HikerFirstInstagramSourceTest가 fake 협력자로 정밀 검증한다.
	 */
	@Test
	void 동기_전용_빈도_같은_체인으로_조립되고_hiker_성공_경로가_동작한다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		InstagramProxyProperties proxyProps =
				new InstagramProxyProperties("", "", null, false, "", "");
		InstagramSource client = new HikerConfig().syncInstagramSource(path -> "{\"user\":{\"pk\":1}}",
				new NoopPayloadRepo(), new BrandCallContext(), new BrandCallCountRepository(null),
				new TargetCallContext(), new TargetCallCountRepository(null), registry, proxyProps,
				new IgSourceSettings(new EmptySettingsRepo(), proxyProps), Duration.ofSeconds(2));

		client.fetchProfile("hypenow");

		Timer timer = registry.find("external.call")
				.tags("api", "hiker", "operation", "profile", "outcome", "ok").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);

		Counter route = registry.find("instagram.source.route")
				.tags("path", "fetchProfile", "backend", "hiker", "outcome", "ok").counter();
		assertThat(route).isNotNull();
		assertThat(route.count()).isEqualTo(1.0);
	}
}
