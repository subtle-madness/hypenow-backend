package com.celfit.was.v1.content;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.SavedLookup;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 6.1 리더보드 — 인증 Optional(SecurityConfig permitAll). 로그인 시에만 카드에 isContentsSaved를 실어
 * 개인화하고, 비로그인이면 필드 자체가 없다(스펙 2절 규약). principal은 익명이면 null(AppUserDetails 미일치).
 */
@RestController
public class V1ContentController {

	private final V1ContentRepository repository;
	private final ContentCardAssembler assembler;
	private final SavedLookup savedLookup;

	public V1ContentController(V1ContentRepository repository, ContentCardAssembler assembler,
			SavedLookup savedLookup) {
		this.repository = repository;
		this.assembler = assembler;
		this.savedLookup = savedLookup;
	}

	@GetMapping("/v1/contents")
	public ApiResponse<List<ContentCard>> contents(
			@AuthenticationPrincipal AppUserDetails principal,
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
		// 로그인 시에만 저장 셋을 1회 조회해 각 카드를 마킹, 비로그인이면 saved=null(필드 부재).
		Set<String> savedCodes = principal == null ? null : savedLookup.savedShortCodes(principal.getUserId());
		List<ContentCard> cards = repository.findCards(query).stream()
				.map(row -> assembler.toCard(row,
						savedCodes == null ? null : savedCodes.contains(row.shortCode())))
				.toList();
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", repository.countCards(query));
		meta.put("limit", query.limit());
		meta.put("distributors", repository.findDistributorOptions());
		return ApiResponse.ok(cards, meta);
	}
}
