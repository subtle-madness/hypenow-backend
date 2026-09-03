package com.celfit.was.v2.influencer;

import com.celfit.was.config.CacheConfig;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.influencer.InfluencerCard;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryAssembler;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 6.23 유사 인플루언서 조립 — 기준 계정 단일 키 Redis 캐시(TTL 6h, V2InfluencerReportService와 같은
 * 등급·관용구). 404(기준 계정 없음)는 예외라 캐시에 안 실린다. 빈 목록도 정상 결과라 캐시된다.
 *
 * 09-03 도입 근거: 유사도 쿼리(findSimilarHandles)는 후보 풀 전체를 점수화해 운영 실측 1.46초
 * (09-01 수리 후)이고, 상세 패널을 열 때마다 ai-report와 함께 호출된다. 재료(피어·카테고리 뷰,
 * 계정 카피)는 새벽 배치 후 하루 불변이라 TTL 백스톱만으로 충분하다.
 *
 * 캐시 값은 List 최상위가 아니라 record 래퍼(SimilarInfluencers) — 불변 List(toList)를 값 최상위로
 * 두면 직렬화기가 구현 클래스명을 박아 역직렬화가 깨진다. 발굴 DiscoveryPage와 같은 구조.
 */
@Service
public class V2SimilarInfluencerService {

	/** 유사도 내림차순 카드 목록(서버 고정 최대 10). */
	public record SimilarInfluencers(List<InfluencerCard> cards) {
	}

	private final V2InfluencerReportRepository repository;
	private final V1InfluencerDiscoveryRepository discoveryRepository;
	private final V1InfluencerDiscoveryAssembler discoveryAssembler;

	public V2SimilarInfluencerService(V2InfluencerReportRepository repository,
			V1InfluencerDiscoveryRepository discoveryRepository,
			V1InfluencerDiscoveryAssembler discoveryAssembler) {
		this.repository = repository;
		this.discoveryRepository = discoveryRepository;
		this.discoveryAssembler = discoveryAssembler;
	}

	@Cacheable(cacheNames = CacheConfig.INFLUENCER_SIMILAR, key = "#influencerId", sync = true)
	public SimilarInfluencers similar(String influencerId) {
		if (repository.findSummary(influencerId).isEmpty()) {
			throw V1ApiException.notFound("인플루언서를 찾을 수 없습니다.");
		}
		// 축은 대상 계정에서 파생(F&B 단독 → fnb) — 후보 풀·믹스·카드 비중이 같은 축을 따라간다.
		boolean fnbAxis = repository.findFnbAxis(influencerId);
		List<String> handles = repository.findSimilarHandles(influencerId, fnbAxis);
		List<InfluencerCard> cards = discoveryAssembler.toCards(
				discoveryRepository.findCardsByHandles(handles),
				discoveryRepository.findShares(handles, fnbAxis),
				discoveryRepository.findBrands(handles),
				discoveryRepository.findThumbs(handles),
				discoveryRepository.findEngagements(handles));
		// 카드 조회는 순서 비보장 — 유사도 순(handles) 복원
		Map<String, InfluencerCard> byId = cards.stream()
				.collect(Collectors.toMap(InfluencerCard::id, Function.identity()));
		return new SimilarInfluencers(
				handles.stream().map(byId::get).filter(Objects::nonNull).toList());
	}
}
