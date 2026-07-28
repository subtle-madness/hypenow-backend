package com.celfit.was.v1.influencer;

import com.celfit.was.v1.common.ApiResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 6.21 발굴 목록 — 인증 Public(SecurityConfig permitAll): 비로그인 공개 페이지고 응답에 개인화 필드가
 * 없다(카드 저장 여부는 프론트가 6.9 저장 목록 캐시에서 파생). 쿼리 파라미터는 camelCase(6.1 관례) —
 * 프론트 앱 URL snake_case와의 변환은 프론트 fetch 레이어 책임.
 */
@RestController
public class V1InfluencerDiscoveryController {

	private final V1InfluencerDiscoveryRepository repository;
	private final V1InfluencerDiscoveryAssembler assembler;

	public V1InfluencerDiscoveryController(V1InfluencerDiscoveryRepository repository,
			V1InfluencerDiscoveryAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@GetMapping("/v1/influencers")
	public ApiResponse<List<InfluencerCard>> influencers(
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String mainCategory,
			@RequestParam(required = false) String midCategory,
			@RequestParam(required = false) String subCategory,
			@RequestParam(required = false) String follower,
			@RequestParam(required = false) String activity,
			@RequestParam(required = false) String sponsored,
			@RequestParam(required = false) String contact,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Integer offset) {
		V1InfluencerDiscoveryQuery query = V1InfluencerDiscoveryQuery.of(q, mainCategory,
				midCategory, subCategory, follower, activity, sponsored, contact, sort, limit,
				offset);
		List<V1InfluencerDiscoveryRepository.CardRow> rows = repository.findCards(query);
		List<String> handles = rows.stream()
				.map(V1InfluencerDiscoveryRepository.CardRow::handle).toList();
		List<InfluencerCard> cards = assembler.toCards(rows, repository.findShares(handles),
				repository.findBrands(handles), repository.findThumbs(handles),
				repository.findEngagements(handles));
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", repository.countCards(query));
		meta.put("limit", query.limit());
		meta.put("offset", query.offset());
		return ApiResponse.ok(cards, meta);
	}
}
