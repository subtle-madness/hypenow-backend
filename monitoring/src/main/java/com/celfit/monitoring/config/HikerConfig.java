package com.celfit.monitoring.config;

import com.celfit.instagram.source.FailoverInstagramSource;
import com.celfit.instagram.source.HikerBackend;
import com.celfit.instagram.source.HikerHttp;
import com.celfit.instagram.source.InstagramSource;
import com.celfit.instagram.source.self.DirectCommentFetcher;
import com.celfit.instagram.source.self.EmbedPostFetcher;
import com.celfit.instagram.source.self.ProxyConfig;
import com.celfit.instagram.source.self.SelfCrawlBackend;
import com.celfit.instagram.source.self.SelfHttpClient;
import com.celfit.instagram.source.self.SelfRetry;
import com.celfit.instagram.source.self.SurfaceCircuitBreaker;
import com.celfit.instagram.source.self.WpiProfileFetcher;
import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.CountingHikerHttp;
import com.celfit.monitoring.hiker.InstagramProxyProperties;
import com.celfit.monitoring.hiker.RecordingHikerHttp;
import com.celfit.monitoring.hiker.TargetCallContext;
import com.celfit.monitoring.hiker.TimedHikerHttp;
import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.RawPayloadRepository;
import com.celfit.monitoring.store.TargetCallCountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HikerConfig {

	/**
	 * 수집 진입점 — 소비자는 이 InstagramSource를 주입받는다. 전송 데코레이터 체인(과금·원형 적재·
	 * 지연 메트릭)은 그대로 유지되고, 그 위에 Hiker 파싱 백엔드(HikerBackend)와 정책 계층
	 * (FailoverInstagramSource)을 얹는다. 마일스톤 B: SelfCrawlBackend가 Failover 안에 조립되지만
	 * selfEnabled=false(기본)라 수집은 전량 Hiker로 위임한다(행동 변화 0) — 개통은 마일스톤 C.
	 *
	 * <p>전송 위임자는 HikerHttp 단일 빈이라, 테스트가 {@code @Primary}로 가짜 전송을 꽂아도
	 * 같은 데코레이터 체인(과금·원형 적재·타이머)에 그대로 감싸여 실전과 동일 경로로 동작한다.
	 */
	@Bean
	public InstagramSource instagramSource(HikerHttp transport, RawPayloadRepository rawPayloads,
			BrandCallContext brandContext, BrandCallCountRepository brandCounts,
			TargetCallContext targetContext, TargetCallCountRepository targetCounts,
			MeterRegistry meterRegistry, InstagramProxyProperties proxyProps) {
		// 집계가 바깥 — 원형 적재까지 끝난 "호출자가 성공으로 본 콜"과 집계가 1:1로 맞는다.
		// 타이머는 최내곽(전송 바로 바깥) — 원형 적재·집계의 DB 쓰기 시간이 외부 구간 지표에 안 섞인다.
		HikerHttp chain = new CountingHikerHttp(
				new RecordingHikerHttp(new TimedHikerHttp(transport, meterRegistry), rawPayloads),
				brandContext, brandCounts, targetContext, targetCounts);
		HikerBackend hikerBackend = new HikerBackend(chain);

		ProxyConfig proxyConfig = new ProxyConfig(proxyProps.residentialUrl(), proxyProps.mobileUrl(),
				proxyProps.requestTimeout(), proxyProps.geoKr());
		SelfHttpClient httpClient = new SelfHttpClient(proxyConfig);
		SelfCrawlBackend self = new SelfCrawlBackend(
				new EmbedPostFetcher(httpClient::get),
				new WpiProfileFetcher(httpClient::get),
				new DirectCommentFetcher(httpClient, proxyProps.commentDocId(), proxyProps.commentFriendlyName()),
				new SurfaceCircuitBreaker(5),
				new SelfRetry(3));

		return new FailoverInstagramSource(self, hikerBackend, proxyProps.selfEnabled());
	}
}
