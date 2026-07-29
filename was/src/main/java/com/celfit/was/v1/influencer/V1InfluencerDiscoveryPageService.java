package com.celfit.was.v1.influencer;

import com.celfit.was.config.CacheConfig;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 6.21 발굴 목록 페이지 묶음 — Redis 캐시 단위(스펙 §4). 응답에 개인화 필드가 없어(저장 여부는
 * 프론트가 6.9 캐시에서 파생) 본 쿼리+보강 4쿼리+조립까지 통째로 캐싱한다.
 */
@Service
public class V1InfluencerDiscoveryPageService {

	private final V1InfluencerDiscoveryRepository repository;
	private final V1InfluencerDiscoveryAssembler assembler;

	public V1InfluencerDiscoveryPageService(V1InfluencerDiscoveryRepository repository,
			V1InfluencerDiscoveryAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@Cacheable(cacheNames = CacheConfig.INFLUENCER_DISCOVERY, key = "#q.cacheKey()", sync = true)
	public DiscoveryPage page(V1InfluencerDiscoveryQuery q) {
		List<V1InfluencerDiscoveryRepository.CardRow> rows = repository.findCards(q);
		List<String> handles = rows.stream()
				.map(V1InfluencerDiscoveryRepository.CardRow::handle).toList();
		List<InfluencerCard> cards = assembler.toCards(rows, repository.findShares(handles),
				repository.findBrands(handles), repository.findThumbs(handles),
				repository.findEngagements(handles));
		return new DiscoveryPage(cards, repository.countCards(q));
	}

	/** 캐시에 실리는 페이지 묶음 — 조립 완료 카드(개인화 없음). */
	public record DiscoveryPage(List<InfluencerCard> cards, long total) {
	}
}
