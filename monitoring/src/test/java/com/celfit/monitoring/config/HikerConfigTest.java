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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

	/** DB 없는 설정 스텁 — 맵에 넣은 값만 반환(사용자 트리거 토글 테스트 전용). */
	private static final class MapSettingsRepo extends AppSettingRepository {
		private final Map<String, String> values;

		MapSettingsRepo(Map<String, String> values) {
			super(null);
			this.values = values;
		}

		@Override
		public Optional<String> find(String key) {
			return Optional.ofNullable(values.get(key));
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

	// ── userTriggeredInstagramSource — 사용자 트리거 비동기 흐름 도입 시점 토글(2026-09) ──────
	// 두 위임 대상(instagramSource·syncInstagramSource)을 각자 다른 fake 전송으로 조립해, 라우팅
	// 빈이 실제로 어느 쪽으로 콜을 보내는지 콜 목록으로 구분한다(둘 다 self-enabled=false 상태라
	// 내부적으로는 항상 자기 자신의 hiker로 가므로, "어느 리스트에 쌓였는가"가 곧 라우팅 결과다).

	private InstagramSource assemble(HikerConfig config, List<String> calls, IgSourceSettings igSettings,
			boolean sync) {
		InstagramProxyProperties proxyProps = new InstagramProxyProperties("", "", null, false, "", "");
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		if (sync) {
			return config.syncInstagramSource(path -> {
				calls.add(path);
				return "{\"user\":{\"pk\":1}}";
			}, new NoopPayloadRepo(), new BrandCallContext(), new BrandCallCountRepository(null),
					new TargetCallContext(), new TargetCallCountRepository(null), registry, proxyProps,
					igSettings, Duration.ofSeconds(2));
		}
		return config.instagramSource(path -> {
			calls.add(path);
			return "{\"user\":{\"pk\":1}}";
		}, new NoopPayloadRepo(), new BrandCallContext(), new BrandCallCountRepository(null),
				new TargetCallContext(), new TargetCallCountRepository(null), registry, proxyProps,
				igSettings, Duration.ofSeconds(8));
	}

	@Test
	void 토글_off_시드값이면_사용자_트리거_라우팅_빈은_syncInstagramSource로만_간다() {
		HikerConfig config = new HikerConfig();
		IgSourceSettings igSettings = new IgSourceSettings(new MapSettingsRepo(new HashMap<>()),
				new InstagramProxyProperties("", "", null, false, "", ""));
		List<String> selfFirstCalls = new ArrayList<>();
		List<String> hikerFirstCalls = new ArrayList<>();
		InstagramSource selfFirst = assemble(config, selfFirstCalls, igSettings, false);
		InstagramSource hikerFirst = assemble(config, hikerFirstCalls, igSettings, true);
		InstagramSource routed = config.userTriggeredInstagramSource(selfFirst, hikerFirst, igSettings);

		routed.fetchProfile("hypenow");

		assertThat(hikerFirstCalls).isNotEmpty();
		assertThat(selfFirstCalls).isEmpty();
	}

	@Test
	void 토글_on이면_사용자_트리거_라우팅_빈은_instagramSource로만_간다() {
		HikerConfig config = new HikerConfig();
		Map<String, String> values = new HashMap<>();
		values.put("ig-source.self-user-triggered", "true");
		IgSourceSettings igSettings = new IgSourceSettings(new MapSettingsRepo(values),
				new InstagramProxyProperties("", "", null, false, "", ""));
		List<String> selfFirstCalls = new ArrayList<>();
		List<String> hikerFirstCalls = new ArrayList<>();
		InstagramSource selfFirst = assemble(config, selfFirstCalls, igSettings, false);
		InstagramSource hikerFirst = assemble(config, hikerFirstCalls, igSettings, true);
		InstagramSource routed = config.userTriggeredInstagramSource(selfFirst, hikerFirst, igSettings);

		routed.fetchProfile("hypenow");

		assertThat(selfFirstCalls).isNotEmpty();
		assertThat(hikerFirstCalls).isEmpty();
	}
}
