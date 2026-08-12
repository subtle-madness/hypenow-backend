package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브랜드 게시물 표면(스펙 §6-1·§6-2·§6-4) — 목록·상세 + 직접 등록 접수·상태 조회. 인증 필수,
 * monitoring 비활성 환경에선 표면 자체가 없다(빈 미등록 → 404, 계정 컨트롤러와 같은 게이트).
 *
 * <p>필터·정렬은 전부 메모리다 — 대상이 브랜드 1계정의 90일 윈도우(~105건 + 직접 등록분)라
 * 페이지네이션 없이 전량을 조립한 뒤 자른다. {@code meta.counts}는 필터 적용 <b>전</b> 전량 기준이라
 * FE가 탭 뱃지를 그릴 때 자기 필터 때문에 숫자가 흔들리지 않는다.
 */
@RestController
@RequestMapping("/v1/brand-monitoring")
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class V1BrandPostsController {

	/**
	 * 목록 상한(FE 명세 meta.limit) — 수집 안전 밸브(monitoring {@code max-posts-per-sweep:2000})와
	 * 같은 값으로, 정상 경로에선 도달하지 않는 폭주 방어다. 구 200은 90일·105건 시절의 값이라
	 * 정책 v1(365일 윈도우·저장소 상한 폐지) 이후 12개월치가 많은 브랜드(실측 463건)를 실제로
	 * 잘랐다 — 잘린 것은 정렬 뒤쪽, 즉 새로 백필된 소급분이었다.
	 */
	private static final int POST_LIMIT = 2000;

	private static final String FILTER_ALL = "all";
	private static final String SORT_UPLOADED_DESC = "uploaded_desc";
	private static final String SORT_PERFORMANCE_DESC = "performance_desc";

	/** body 없는 POST(RequestBody required=false → null) 정규화 상수 — 검증은 서비스가 한다. */
	private static final BrandDirectPostRegisterRequest EMPTY_DIRECT_REQUEST =
			new BrandDirectPostRegisterRequest(null, null, null);

	private final BrandLinkRepository linkRepository;
	private final BrandReadRepository brandReadRepository;
	private final BrandPostAssembler assembler;
	private final V1BrandDirectPostService directPostService;

	public V1BrandPostsController(BrandLinkRepository linkRepository, BrandReadRepository brandReadRepository,
			BrandPostAssembler assembler, V1BrandDirectPostService directPostService) {
		this.linkRepository = linkRepository;
		this.brandReadRepository = brandReadRepository;
		this.assembler = assembler;
		this.directPostService = directPostService;
	}

	@GetMapping("/accounts/{accountId}/posts")
	public ApiResponse<List<BrandPostResponse>> list(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) String sponsorship,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String uploadedFrom,
			@RequestParam(required = false) String uploadedTo) {
		long brandId = parseAccountId(accountId);
		requireOwnership(principal.getUserId(), brandId);
		BrandAccountRow account = findAccountOrThrow(brandId);

		String sourceFilter = normalizeFilter(source, "source", BrandPostAssembler.SOURCE_TAGGED,
				BrandPostAssembler.SOURCE_DIRECT, BrandPostAssembler.SOURCE_HASHTAG);
		String sponsorshipFilter = normalizeFilter(sponsorship, "sponsorship", BrandSponsorshipClassifier.SPONSORED,
				BrandSponsorshipClassifier.ORGANIC, BrandSponsorshipClassifier.UNKNOWN);
		String sortKey = normalizeSort(sort);
		LocalDate from = parseDate(uploadedFrom, "uploadedFrom");
		LocalDate to = parseDate(uploadedTo, "uploadedTo");

		List<BrandPostResponse> all = assembler.assembleForBrand(principal.getUserId(), account);
		List<BrandPostResponse> filtered = all.stream()
				.filter(p -> sourceFilter == null || sourceFilter.equals(p.source()))
				.filter(p -> sponsorshipFilter == null || sponsorshipFilter.equals(p.sponsorship()))
				.filter(p -> withinUploadWindow(p, from, to))
				.sorted(comparator(sortKey))
				.limit(POST_LIMIT)
				.toList();

		return ApiResponse.ok(filtered, meta(filtered.size(), all, account));
	}

	/**
	 * 상세(§6-2) — postId는 shortcode다. 경로에 브랜드가 없어 연결된 브랜드 전체(다계정)를 순서대로
	 * 뒤진다 — 어느 목록에도 없으면 존재 여부를 흘리지 않고 404.
	 */
	@GetMapping("/posts/{postId}")
	public ApiResponse<BrandPostResponse> get(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String postId) {
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(principal.getUserId())) {
			Optional<BrandAccountRow> account = brandReadRepository.findAccount(link.brandId());
			if (account.isEmpty()) {
				continue;
			}
			Optional<BrandPostResponse> found = assembler.assembleForBrand(principal.getUserId(), account.get())
					.stream()
					.filter(p -> p.id().equals(postId))
					.findFirst();
			if (found.isPresent()) {
				return ApiResponse.ok(found.get());
			}
		}
		throw postNotFound();
	}

	// ---------- 직접 등록(§6-4) ----------

	/**
	 * 직접 등록 접수 — 항상 202다. 레거시 등록 파이프라인이 실제 게시물 확인을 비동기로 하므로
	 * 접수 시점에는 entry별 판정(duplicate·failed)만 확정되고 신규분은 pending이다.
	 * 진행은 {@link #directRegistration} 폴링으로 확인한다.
	 */
	@PostMapping("/accounts/{accountId}/direct-posts")
	public ResponseEntity<ApiResponse<BrandDirectRegistrationResponse>> registerDirectPosts(
			@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId,
			@RequestBody(required = false) BrandDirectPostRegisterRequest body) {
		BrandDirectPostRegisterRequest request = body == null ? EMPTY_DIRECT_REQUEST : body;
		BrandDirectRegistrationResponse response = directPostService.register(principal.getUserId(),
				parseAccountId(accountId), request.postUrls(), request.trackingDays(), request.campaignId());
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
	}

	/** 등록 상태 폴링 — 접수 응답과 같은 셰이프. 남의 등록·없는 등록은 구분 없이 404. */
	@GetMapping("/direct-registrations/{registrationId}")
	public ApiResponse<BrandDirectRegistrationResponse> directRegistration(
			@AuthenticationPrincipal AppUserDetails principal, @PathVariable String registrationId) {
		return ApiResponse.ok(directPostService.get(principal.getUserId(), registrationId));
	}

	/**
	 * 직접 등록 요청 본문 — postUrls는 게시물 링크 문자열(정규화·검증은 레거시 {@code MonitoringInput}),
	 * campaignId는 문자열 id(레거시 계약과 동일하게 그대로 전달한다).
	 */
	public record BrandDirectPostRegisterRequest(List<String> postUrls, Integer trackingDays, String campaignId) {
	}

	// ---------- meta ----------

	private static Map<String, Object> meta(int total, List<BrandPostResponse> all, BrandAccountRow account) {
		Map<String, Object> counts = new LinkedHashMap<>();
		counts.put(FILTER_ALL, all.size());
		counts.put(BrandPostAssembler.SOURCE_TAGGED, count(all, BrandPostResponse::source,
				BrandPostAssembler.SOURCE_TAGGED));
		counts.put(BrandPostAssembler.SOURCE_DIRECT, count(all, BrandPostResponse::source,
				BrandPostAssembler.SOURCE_DIRECT));
		counts.put(BrandPostAssembler.SOURCE_HASHTAG, count(all, BrandPostResponse::source,
				BrandPostAssembler.SOURCE_HASHTAG));
		counts.put(BrandSponsorshipClassifier.SPONSORED, count(all, BrandPostResponse::sponsorship,
				BrandSponsorshipClassifier.SPONSORED));
		counts.put(BrandSponsorshipClassifier.ORGANIC, count(all, BrandPostResponse::sponsorship,
				BrandSponsorshipClassifier.ORGANIC));
		counts.put(BrandSponsorshipClassifier.UNKNOWN, count(all, BrandPostResponse::sponsorship,
				BrandSponsorshipClassifier.UNKNOWN));

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", total);
		meta.put("limit", POST_LIMIT);
		meta.put("counts", counts);
		// 브랜드 화면의 "마지막 수집"은 브랜드 스윕 시각이다(직접 등록분의 레거시 스윕과 별개).
		meta.put("lastCollectedAt", KstTimestamps.toKstIso(account.lastSweptAt()));
		return meta;
	}

	private static long count(List<BrandPostResponse> all,
			Function<BrandPostResponse, String> field, String value) {
		return all.stream().filter(p -> value.equals(field.apply(p))).count();
	}

	// ---------- 필터·정렬 ----------

	private static boolean withinUploadWindow(BrandPostResponse post, LocalDate from, LocalDate to) {
		if (from == null && to == null) {
			return true;
		}
		LocalDate uploadedOn = BrandPostAssembler.uploadedOn(post);
		if (uploadedOn == null) {
			// 업로드일을 모르는 건(수집 전 직접 등록분)은 기간 필터를 걸면 판정 불가라 제외한다.
			return false;
		}
		return (from == null || !uploadedOn.isBefore(from)) && (to == null || !uploadedOn.isAfter(to));
	}

	/**
	 * performance_desc = 최신 스냅샷 views 내림차순, null(미수집·피드) 마지막. 동률·null 구간은
	 * 업로드 최신순으로 다시 정렬해 순서가 요청마다 흔들리지 않게 한다.
	 */
	private static Comparator<BrandPostResponse> comparator(String sortKey) {
		Comparator<BrandPostResponse> uploadedDesc = Comparator
				.comparing(BrandPostAssembler::uploadedOn, Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(BrandPostResponse::shortcode);
		if (!SORT_PERFORMANCE_DESC.equals(sortKey)) {
			return uploadedDesc;
		}
		return Comparator.comparing(V1BrandPostsController::latestViews,
				Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(uploadedDesc);
	}

	private static Long latestViews(BrandPostResponse post) {
		TrackingItemResponse.SnapshotResponse latest = post.latestSnapshot();
		return latest == null ? null : latest.views();
	}

	/** 미지정·{@code all}은 필터 없음(null), 그 외 값은 허용 목록 밖이면 400. */
	private static String normalizeFilter(String raw, String param, String... allowed) {
		if (raw == null || raw.isBlank() || FILTER_ALL.equals(raw)) {
			return null;
		}
		for (String candidate : allowed) {
			if (candidate.equals(raw)) {
				return raw;
			}
		}
		throw V1ApiException.validation(param + " 값이 올바르지 않아요.");
	}

	private static String normalizeSort(String raw) {
		if (raw == null || raw.isBlank() || SORT_UPLOADED_DESC.equals(raw)) {
			return SORT_UPLOADED_DESC;
		}
		if (SORT_PERFORMANCE_DESC.equals(raw)) {
			return SORT_PERFORMANCE_DESC;
		}
		throw V1ApiException.validation("sort 값이 올바르지 않아요.");
	}

	private static LocalDate parseDate(String raw, String param) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(raw);
		} catch (DateTimeParseException e) {
			throw V1ApiException.validation(param + "은 YYYY-MM-DD 형식이어야 해요.");
		}
	}

	// ---------- 공용 ----------

	private void requireOwnership(long userId, long brandId) {
		Optional<BrandLinkRow> link = linkRepository.findActiveByUserAndBrand(userId, brandId);
		if (link.isEmpty()) {
			throw V1ApiException.forbidden("FORBIDDEN", "브랜드 계정을 찾을 수 없거나 접근 권한이 없어요.");
		}
	}

	private BrandAccountRow findAccountOrThrow(long brandId) {
		return brandReadRepository.findAccount(brandId)
				.orElseThrow(() -> V1ApiException.notFound("브랜드 계정을 찾을 수 없습니다."));
	}

	/** accountId는 문자열 path 파라미터라 숫자가 아니면 존재할 수 없는 id → 404(계정 컨트롤러 관용구). */
	private static long parseAccountId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw V1ApiException.notFound("브랜드 계정을 찾을 수 없습니다.");
		}
	}

	private static V1ApiException postNotFound() {
		return V1ApiException.notFound("게시물을 찾을 수 없습니다.");
	}
}
