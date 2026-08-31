package com.celfit.was.v1.influencer;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.account.RateLimiter;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.PagePrefetcher;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryPageService.DiscoveryPage;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 6.21 발굴 목록 — 인증 Public(SecurityConfig permitAll): 비로그인 공개 페이지고 응답에 개인화 필드가
 * 없다(카드 저장 여부는 프론트가 6.9 저장 목록 캐시에서 파생). 페이지는 Redis 캐시(pageService).
 * 쿼리 파라미터는 camelCase(6.1 관례) — 프론트 앱 URL snake_case와의 변환은 프론트 fetch 레이어 책임.
 *
 * 비로그인 무제한 접근으로 클로즈베타 핵심 데이터가 스크래핑되는 걸 막기 위해 IP 레이트리밋을 둔다
 * (기존 RateLimiter 재사용 — V1AuthController·V1GateEventController와 동일 관용구). 익명은 IP,
 * 로그인 사용자는 user_id 단위로 키를 분리한다 — 사무실 NAT 등 IP 공유 환경에서 동료의 조회량 때문에
 * 억울하게 막히지 않게 하려는 것. 익명 60/분은 email-availability(30/분, 디바운스 타이핑 전제)보다
 * 넉넉하게 잡았다 — 목록은 필터·정렬 변경마다 재요청되는 정상 스크롤 패턴이 더 잦기 때문. 로그인
 * 120/분은 개별 사용자 키라 IP 공유 우려가 없어 더 느슨하게 둔다.
 */
@RestController
public class V1InfluencerDiscoveryController {

	private static final int ANON_PER_MINUTE = 60;
	private static final int AUTH_PER_MINUTE = 120;

	private final V1InfluencerDiscoveryPageService pageService;
	private final PagePrefetcher prefetcher;
	private final RateLimiter rateLimiter;

	public V1InfluencerDiscoveryController(V1InfluencerDiscoveryPageService pageService,
			PagePrefetcher prefetcher, RateLimiter rateLimiter) {
		this.pageService = pageService;
		this.prefetcher = prefetcher;
		this.rateLimiter = rateLimiter;
	}

	@GetMapping("/v1/influencers")
	public ApiResponse<List<InfluencerCard>> influencers(
			@AuthenticationPrincipal AppUserDetails principal,
			HttpServletRequest httpRequest,
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
			@RequestParam(required = false) Integer offset,
			@RequestParam(required = false) String vertical) {
		if (!rateLimiter.tryAcquire(rateLimitKey("influencers-list", principal, httpRequest),
				principal == null ? ANON_PER_MINUTE : AUTH_PER_MINUTE)) {
			throw V1ApiException.rateLimited();
		}
		V1InfluencerDiscoveryQuery query = V1InfluencerDiscoveryQuery.of(q, mainCategory,
				midCategory, subCategory, follower, activity, sponsored, contact, sort, limit,
				offset, vertical);
		DiscoveryPage page = pageService.page(query);
		if (PagePrefetcher.hasNextPage(page.cards().size(), query.limit(), query.offset(), page.total())) {
			prefetcher.prefetch(() -> pageService.page(query.next()));
		}
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", page.total());
		meta.put("limit", query.limit());
		meta.put("offset", query.offset());
		return ApiResponse.ok(page.cards(), meta);
	}

	/** 익명은 IP, 로그인 사용자는 user_id로 키를 분리(사무실 NAT 오탐 방지 — 클래스 주석 참고). */
	private static String rateLimitKey(String prefix, AppUserDetails principal, HttpServletRequest httpRequest) {
		return principal == null
				? prefix + ":ip:" + httpRequest.getRemoteAddr()
				: prefix + ":user:" + principal.getUserId();
	}
}
