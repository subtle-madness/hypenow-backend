package com.celfit.was.v1.content;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.PagePrefetcher;
import com.celfit.was.v1.common.SavedLookup;
import com.celfit.was.v1.content.V1ContentPageService.ContentPage;
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
 * 6.1 리더보드 — 인증 필수(로그인 월, 비로그인은 401). 공통 페이지는 Redis 캐시(pageService),
 * 로그인한 사용자마다 카드에 isContentsSaved를 캐시 밖에서 오버레이한다(스펙 2절 규약).
 * principal은 스웨거·테스트 등에서 인증이 우회된 경로면 null일 수 있어 방어적으로 분기한다
 * (AppUserDetails 미일치 시 null).
 */
@RestController
public class V1ContentController {

	private final V1ContentPageService pageService;
	private final ContentCardAssembler assembler;
	private final SavedLookup savedLookup;
	private final PagePrefetcher prefetcher;

	public V1ContentController(V1ContentPageService pageService, ContentCardAssembler assembler,
			SavedLookup savedLookup, PagePrefetcher prefetcher) {
		this.pageService = pageService;
		this.assembler = assembler;
		this.savedLookup = savedLookup;
		this.prefetcher = prefetcher;
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
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Integer offset) {
		V1ContentQuery query = V1ContentQuery.of(startDate, endDate, contentType, mainCategory,
				midCategory, subCategory, follower, keyword, adType, distributorId, sort, limit,
				offset);
		ContentPage page = pageService.page(query);
		// 로그인 시에만 저장 셋을 1회 조회해 각 카드를 마킹, 비로그인이면 saved=null(필드 부재).
		Set<String> savedCodes = principal == null ? null : savedLookup.savedShortCodes(principal.getUserId());
		List<ContentCard> cards = page.rows().stream()
				.map(row -> assembler.toCard(row,
						savedCodes == null ? null : savedCodes.contains(row.shortCode())))
				.toList();
		if (PagePrefetcher.hasNextPage(page.rows().size(), query.limit(), query.offset(), page.total())) {
			prefetcher.prefetch(() -> pageService.page(query.next()));
		}
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", page.total());
		meta.put("limit", query.limit());
		meta.put("offset", query.offset());
		meta.put("distributors", pageService.distributorOptions());
		return ApiResponse.ok(cards, meta);
	}
}
