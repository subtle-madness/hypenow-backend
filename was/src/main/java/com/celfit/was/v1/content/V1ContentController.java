package com.celfit.was.v1.content;

import com.celfit.was.v1.common.ApiResponse;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 6.1 리더보드 — 인증 Optional이나 P2 전이라 개인화 필드 없이 서빙(스펙상 비로그인 응답과 동일). */
@RestController
public class V1ContentController {

	private final V1ContentRepository repository;
	private final ContentCardAssembler assembler;

	public V1ContentController(V1ContentRepository repository, ContentCardAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@GetMapping("/v1/contents")
	public ApiResponse<List<ContentCard>> contents(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
			@RequestParam(required = false) String contentType,
			@RequestParam(required = false) String mainCategory,
			@RequestParam(required = false) String midCategory,
			@RequestParam(required = false) String subCategory,
			@RequestParam(required = false) String follower,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String adType,
			@RequestParam(required = false) String distributorId,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) Integer limit) {
		V1ContentQuery query = V1ContentQuery.of(startDate, endDate, contentType, mainCategory,
				midCategory, subCategory, follower, keyword, adType, distributorId, sort, limit);
		List<ContentCard> cards = repository.findCards(query).stream().map(assembler::toCard).toList();
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", repository.countCards(query));
		meta.put("limit", query.limit());
		meta.put("distributors", repository.findDistributorOptions());
		return ApiResponse.ok(cards, meta);
	}
}
