package com.celfit.was.v1.influencer;

import com.celfit.was.config.CacheConfig;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 6.21 발굴 목록 페이지 묶음 — Redis 캐시 단위(스펙 §4). 응답에 개인화 필드가 없어(저장 여부는
 * 프론트가 6.9 캐시에서 파생) 본 쿼리+보강 4쿼리+조립까지 통째로 캐싱한다.
 * activity 필터는 SQL now() 기준이라 캐시가 KST 자정을 최대 TTL(1h)만큼 넘길 수 있다 —
 * TTL 연장 시 재검토.
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
		List<InfluencerCard> cards = assembler.toCards(rows, repository.findShares(handles, q.fnbAxis()),
				repository.findBrands(handles), repository.findThumbs(handles),
				repository.findEngagements(handles), repository.findGroupPurchaseCounts(handles));
		// total은 본 쿼리 윈도우(count(*) OVER ()) — countCards 재실행은 0행(offset 초과·공집합)
		// 폴백뿐이다(2026-08-27 count 통합).
		long total = rows.isEmpty() ? repository.countCards(q) : rows.getFirst().totalCount();
		return new DiscoveryPage(cards, total);
	}

	/** 캐시에 실리는 페이지 묶음 — 조립 완료 카드(개인화 없음). */
	public record DiscoveryPage(List<InfluencerCard> cards, long total) {
	}
}
