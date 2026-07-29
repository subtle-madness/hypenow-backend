package com.celfit.was.v1.influencer;

import com.celfit.was.config.CacheConfig;
import com.celfit.was.v1.common.V1ApiException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** 6.5 리포트 조립 — 단일 리소스 키 Redis 캐시(TTL 6h, 스펙 §4). 404는 예외라 캐시에 안 실린다. */
@Service
public class V1InfluencerReportService {

	private final V1InfluencerReportRepository repository;
	private final V1InfluencerReportAssembler assembler;

	public V1InfluencerReportService(V1InfluencerReportRepository repository,
			V1InfluencerReportAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@Cacheable(cacheNames = CacheConfig.INFLUENCER_REPORT, key = "#influencerId", sync = true)
	public InfluencerAiReport report(String influencerId) {
		var summary = repository.findSummary(influencerId)
				.orElseThrow(() -> V1ApiException.notFound("인플루언서를 찾을 수 없습니다."));
		return assembler.toReport(summary,
				repository.findLatestCopy(influencerId).orElse(null),
				repository.findSeries(influencerId),
				repository.findCategories(influencerId),
				repository.findBrands(influencerId));
	}
}
