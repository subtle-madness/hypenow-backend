package com.celfit.monitoring.config;

import com.celfit.instagram.source.FailoverInstagramSource;
import com.celfit.instagram.source.HikerBackend;
import com.celfit.instagram.source.HikerFirstInstagramSource;
import com.celfit.instagram.source.HikerHttp;
import com.celfit.instagram.source.InstagramSource;
import com.celfit.instagram.source.ToggledInstagramSource;
import com.celfit.instagram.source.self.DirectCommentFetcher;
import com.celfit.instagram.source.self.EmbedPostFetcher;
import com.celfit.instagram.source.self.FeedUserPostsFetcher;
import com.celfit.instagram.source.self.OgProfileFetcher;
import com.celfit.instagram.source.self.ProxyConfig;
import com.celfit.instagram.source.self.SelfCrawlBackend;
import com.celfit.instagram.source.self.SelfHttpClient;
import com.celfit.instagram.source.self.SelfRetry;
import com.celfit.instagram.source.self.SurfaceCircuitBreaker;
import com.celfit.instagram.source.self.WpiProfileFetcher;
import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.ConcurrencyLimitedHikerHttp;
import com.celfit.monitoring.hiker.CountingHikerHttp;
import com.celfit.monitoring.hiker.HikerConcurrencyLimiter;
import com.celfit.monitoring.hiker.HikerConcurrencyLimiter.Lane;
import com.celfit.monitoring.hiker.IgSourceSettings;
import com.celfit.monitoring.hiker.InstagramProxyProperties;
import com.celfit.monitoring.hiker.MicrometerInstagramSourceMetrics;
import com.celfit.monitoring.hiker.RecordingHikerHttp;
import com.celfit.monitoring.hiker.TargetCallContext;
import com.celfit.monitoring.hiker.TimedHikerHttp;
import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.RawPayloadRepository;
import com.celfit.monitoring.store.TargetCallCountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class HikerConfig {

	/**
	 * Hiker 전송 동시 in-flight 상한(2026-09-03) — 아래 체인 4개가 <b>같은 인스턴스</b>를 공유해야
	 * "어떤 풀 조합에서도 총 in-flight ≤ max"가 성립한다. 근거·동기 경로 예약 설계는
	 * {@link HikerConcurrencyLimiter} javadoc 참조.
	 */
	@Bean
	public HikerConcurrencyLimiter hikerConcurrencyLimiter(MeterRegistry meterRegistry,
			@Value("${monitoring.hiker.max-concurrent-calls:14}") int maxConcurrentCalls,
			@Value("${monitoring.hiker.sync-reserved-permits:2}") int syncReservedPermits,
			@Value("${monitoring.hiker.batch-acquire-timeout:60s}") Duration batchAcquireTimeout,
			@Value("${monitoring.hiker.sync-acquire-timeout:3s}") Duration syncAcquireTimeout) {
		return new HikerConcurrencyLimiter(maxConcurrentCalls, syncReservedPermits, batchAcquireTimeout,
				syncAcquireTimeout, meterRegistry);
	}

	/**
	 * 전송 데코레이터 체인 조립(단일 정본) — 새 소비자 빈이 늘어도 동시 상한 데코레이터를 빠뜨릴 수
	 * 없게 한 곳에 모은다. 안쪽부터 전송 → 타이머 → <b>동시 상한</b> → 원형 적재 → 콜 집계 순이다:
	 * 집계가 바깥이라 "호출자가 성공으로 본 콜"과 1:1로 맞고, 타이머는 최내곽이라 원형 적재·집계의
	 * DB 쓰기 시간도, 상한 대기 시간도 외부 구간 지표에 섞이지 않는다.
	 */
	private static HikerHttp chain(HikerHttp transport, Lane lane, HikerConcurrencyLimiter limiter,
			MeterRegistry meterRegistry, RawPayloadRepository rawPayloads, BrandCallContext brandContext,
			BrandCallCountRepository brandCounts, TargetCallContext targetContext,
			TargetCallCountRepository targetCounts) {
		return new CountingHikerHttp(
				new RecordingHikerHttp(
						new ConcurrencyLimitedHikerHttp(new TimedHikerHttp(transport, meterRegistry), limiter,
								lane),
						rawPayloads),
				brandContext, brandCounts, targetContext, targetCounts);
	}

	/**
	 * 수집 진입점 — 소비자는 이 InstagramSource를 주입받는다. 전송 데코레이터 체인(과금·원형 적재·
	 * 지연 메트릭)은 그대로 유지되고, 그 위에 Hiker 파싱 백엔드(HikerBackend)와 정책 계층
	 * (FailoverInstagramSource)을 얹는다. 자체크롤 토글은 app_setting 런타임 판정(IgSourceSettings —
	 * 매 콜 재확인, 킬스위치 포함)이며 경로별(ig-source.self-paths)로도 걸린다(부분 개통 — 예: 프로필만
	 * 빼고 켜기). 시드 기본은 self-enabled=off라 수집은 전량 Hiker로 위임한다(행동 변화 0).
	 * 라우팅 결과는 instagram.source.route 카운터로 관측한다.
	 *
	 * <p>전송 위임자는 HikerHttp 단일 빈이라, 테스트가 {@code @Primary}로 가짜 전송을 꽂아도
	 * 같은 데코레이터 체인(과금·원형 적재·타이머)에 그대로 감싸여 실전과 동일 경로로 동작한다.
	 *
	 * <p>SelfRetry의 시간 예산(기본 8초, monitoring.self-retry.budget)은 최악 45초(3회 × 15초
	 * request-timeout)가 폴백 전에 그대로 새는 것을 막는다(F3) — 예산 초과 시 남은 재시도를 포기하고
	 * 즉시 Hiker로 넘어간다. <b>이 빈은 배치·백그라운드(스케줄러·executor) 소비자 전용이다</b> —
	 * 사용자 대면 동기 HTTP 요청 핸들러(브랜드 등록·direct 게시물 등록·share 해소·캠페인 등록 등)는
	 * 이 빈이 아니라 {@link #syncInstagramSource} 를 주입받는다(구조적 분리 — 동기 경로가 이 빈에
	 * 잘못 배선되면 self 트러블이 다시 응답 지연에 얹힌다). {@code @Primary}인 이유는 압도적 다수인
	 * 배치 소비자가 별도 {@code @Qualifier} 없이 기존 그대로 이 빈을 받게 하기 위함이다.
	 */
	@Bean
	@Primary
	public InstagramSource instagramSource(HikerHttp transport, RawPayloadRepository rawPayloads,
			BrandCallContext brandContext, BrandCallCountRepository brandCounts,
			TargetCallContext targetContext, TargetCallCountRepository targetCounts,
			MeterRegistry meterRegistry, InstagramProxyProperties proxyProps, IgSourceSettings igSettings,
			HikerConcurrencyLimiter limiter,
			@Value("${monitoring.self-retry.budget:8s}") Duration selfRetryBudget) {
		// 배치·백그라운드 소비자 전용 체인 — 동시 상한의 배치 레인(동기 예약분을 침범하지 않는다).
		HikerHttp chain = chain(transport, Lane.BATCH, limiter, meterRegistry, rawPayloads, brandContext,
				brandCounts, targetContext, targetCounts);
		HikerBackend hikerBackend = new HikerBackend(chain);

		ProxyConfig proxyConfig = new ProxyConfig(proxyProps.residentialUrl(), proxyProps.mobileUrl(),
				proxyProps.requestTimeout(), proxyProps.geoKr());
		SelfHttpClient httpClient = new SelfHttpClient(proxyConfig);
		SelfCrawlBackend self = new SelfCrawlBackend(
				new EmbedPostFetcher(httpClient::get),
				new WpiProfileFetcher(httpClient::get),
				new OgProfileFetcher(httpClient::get),
				new FeedUserPostsFetcher(httpClient::get),
				new DirectCommentFetcher(httpClient, igSettings::commentDocId, igSettings::commentFriendlyName),
				new SurfaceCircuitBreaker(5),
				new SelfRetry(3, selfRetryBudget),
				igSettings::profileSurface);

		return new FailoverInstagramSource(self, hikerBackend, igSettings::selfEnabledForPath,
				new MicrometerInstagramSourceMetrics(meterRegistry));
	}

	/**
	 * 사용자 대면 동기 경로 전용 — {@link HikerFirstInstagramSource}(Hiker 1순위 + 장애 시에만 자체
	 * 구조)로 조립한다. 위 {@link #instagramSource}(자체 1순위)와 정확히 반대 우선순위다: 평시(Hiker
	 * 정상)엔 자체크롤 코드를 아예 타지 않아(self가 있어도 hiker 성공이면 호출 자체가 없다) 토글
	 * 상태와 무관하게 self 트러블이 동기 응답 지연에 얹힐 여지가 없고, Hiker가 벤더 장애(5xx·타임아웃)
	 * 로 실패했을 때만(계정 부재·비공개 같은 Hiker의 결정적 판정은 제외) 자체가 구조 수단으로 남는다
	 * (2026-08-27 브랜드 등록 503 사고 — Hiker 장애 시 폴백 수단이 아예 없었다).
	 *
	 * <p>구조 시도는 <b>1회, 짧은 예산</b>(monitoring.self-retry.rescue-budget, 기본 2초)으로 제한한다
	 * — 위 {@link #instagramSource}의 self(SelfRetry 3회·8초 예산)를 그대로 재사용하면 Hiker가 이미
	 * 쓴 시간에 self의 다회 재시도까지 더해져 동기 응답 예산을 다시 넘긴다. 그 대가로 서킷
	 * 브레이커(SurfaceCircuitBreaker)는 위 배치용 self와 <b>공유하지 않는다</b>(별도 인스턴스) —
	 * 상태 공유로 얻는 이득(배치 self가 이미 아는 표면 장애를 구조 시도에서 건너뛰기)보다 두 인스턴스가
	 * 뒤섞이는 복잡도가 커서 단순한 쪽을 택했다. 토글(ig-source.self-enabled 등)은
	 * {@link IgSourceSettings#selfEnabledForPath}를 그대로 재사용 — 꺼져 있으면 구조 시도 자체가
	 * 없다(행동 변화 0).
	 */
	@Bean("syncInstagramSource")
	public InstagramSource syncInstagramSource(HikerHttp transport, RawPayloadRepository rawPayloads,
			BrandCallContext brandContext, BrandCallCountRepository brandCounts,
			TargetCallContext targetContext, TargetCallCountRepository targetCounts,
			MeterRegistry meterRegistry, InstagramProxyProperties proxyProps, IgSourceSettings igSettings,
			HikerConcurrencyLimiter limiter,
			@Value("${monitoring.self-retry.rescue-budget:2s}") Duration rescueBudget) {
		// 사용자 대면 동기 체인 — 동시 상한의 SYNC 레인(예약 퍼밋 덕에 배치 부하에 밀리지 않는다).
		HikerHttp chain = chain(transport, Lane.SYNC, limiter, meterRegistry, rawPayloads, brandContext,
				brandCounts, targetContext, targetCounts);
		HikerBackend hikerBackend = new HikerBackend(chain);

		ProxyConfig proxyConfig = new ProxyConfig(proxyProps.residentialUrl(), proxyProps.mobileUrl(),
				proxyProps.requestTimeout(), proxyProps.geoKr());
		SelfHttpClient httpClient = new SelfHttpClient(proxyConfig);
		SelfCrawlBackend rescueSelf = new SelfCrawlBackend(
				new EmbedPostFetcher(httpClient::get),
				new WpiProfileFetcher(httpClient::get),
				new OgProfileFetcher(httpClient::get),
				new FeedUserPostsFetcher(httpClient::get),
				new DirectCommentFetcher(httpClient, igSettings::commentDocId, igSettings::commentFriendlyName),
				new SurfaceCircuitBreaker(5),
				new SelfRetry(1, rescueBudget),
				igSettings::profileSurface);

		return new HikerFirstInstagramSource(hikerBackend, rescueSelf, igSettings::selfEnabledForPath,
				new MicrometerInstagramSourceMetrics(meterRegistry));
	}

	/**
	 * 사용자 트리거 비동기 흐름(등록 백필·보강·해시태그 스윕·메트릭 백필) 전용 라우팅 빈 — 2026-09
	 * 사용자 트리거 도입 시점 토글(스펙 참조). 위 {@link #instagramSource}(자체 1순위)와
	 * {@link #syncInstagramSource}(Hiker 1순위) 두 완성 빈을 그대로 재사용해 콜마다
	 * {@link IgSourceSettings#selfUserTriggered()}로 위임만 한다({@link ToggledInstagramSource} —
	 * 새 폴백 로직 없음). 토글은 TTL(기본 5초)만큼의 지연으로 재배포 없이 반영된다.
	 *
	 * <p>시드는 false라 사용자 트리거 비동기는 {@link #syncInstagramSource}와 동형(Hiker 1순위 +
	 * 장애 시에만 self 구조)으로 계속 동작한다 — 이미 개통된 새벽 스케줄 트리거(그대로
	 * {@link #instagramSource}를 쓴다)와 도입 시점만 분리됐을 뿐, 최종적으로는 이 빈도 자체 1순위로
	 * 수렴한다(영구 구조가 아니라 단계적 개통 수단).
	 */
	@Bean("userTriggeredInstagramSource")
	public InstagramSource userTriggeredInstagramSource(
			@Qualifier("instagramSource") InstagramSource selfFirst,
			@Qualifier("syncInstagramSource") InstagramSource hikerFirst, IgSourceSettings igSettings) {
		return new ToggledInstagramSource(selfFirst, hikerFirst, igSettings::selfUserTriggered);
	}

	/**
	 * 저장·공유·리포스트 단건 복권 재시도({@code CollectService.retrySinglesOnce}) 전용 — Hiker
	 * 직결(self 완전 미경유, FailoverInstagramSource도 안 씌운다). 2026-09-03 self 셰이프 회귀 수정:
	 * self(EmbedPostFetcher)는 embed HTML에 저장·공유·리포스트 키가 없어 3지표를 구조적으로 항상
	 * null 반환하는데, 그 응답도 예외 없는 "성공"이라 {@link FailoverInstagramSource#route}가
	 * 폴백하지 않는다 — self가 섞인 소스로 이 재시도를 돌리면 매 시도가 결정론적으로 꽝이라
	 * {@code metricsRetryMax}(기본 6회) 재시도가 전부 무력화된다(운영 로그 15전 0승 실측). 창 밖
	 * 판정({@code retryClipsOnce}, self 미지원 하드게이트로 이미 Hiker 폴백)은 이 빈을 쓰지 않는다
	 * — self 절감 목적이 있는 경로라 그대로 호출부 소스(hiker/userTriggeredHiker)를 쓴다.
	 */
	@Bean("metricsRetryInstagramSource")
	public InstagramSource metricsRetryInstagramSource(HikerHttp transport, RawPayloadRepository rawPayloads,
			BrandCallContext brandContext, BrandCallCountRepository brandCounts,
			TargetCallContext targetContext, TargetCallCountRepository targetCounts,
			MeterRegistry meterRegistry, HikerConcurrencyLimiter limiter) {
		// 스윕 꼬리의 복권 재시도 — 배치 레인.
		return new HikerBackend(chain(transport, Lane.BATCH, limiter, meterRegistry, rawPayloads, brandContext,
				brandCounts, targetContext, targetCounts));
	}
}
