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
import java.time.Clock;
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
 * 브랜드 게시물 표면(스펙 §6-1·§6-2·§6-4) — 목록(tagged·direct)·상세 + 직접 등록 접수·상태 조회 +
 * 해시태그 발견 게시물 전용 목록(§8, 별도 탭 결정 2026-08-12). 인증 필수, monitoring 비활성
 * 환경에선 표면 자체가 없다(빈 미등록 → 404, 계정 컨트롤러와 같은 게이트).
 *
 * <p>필터·정렬은 전부 메모리다 — 대상이 브랜드 1계정의 90일 윈도우(~105건 + 직접 등록분)라
 * 페이지네이션 없이 전량을 조립한 뒤 자른다. {@code meta.counts}는 필터 적용 <b>전</b> 전량 기준이라
 * FE가 탭 뱃지를 그릴 때 자기 필터 때문에 숫자가 흔들리지 않는다.
 *
 * <p>단 그 "전량"은 유저의 링크 표시 창(2026-08-17 — {@code brand_monitorings.collection_months})으로
 * 이미 잘린 뒤다: 자산은 유저 간 max로 수집하므로 12개월치가 있어도 3개월 신청 유저에겐 3개월만
 * 보이고, counts도 그 창 기준이라 탭 뱃지가 실제 목록과 어긋나지 않는다. 상세도 같은 창이다.
 *
 * <p>해시태그 발견 게시물은 §6-1 목록에 <b>병합하지 않는다</b>(2026-08-12 결정 — 별도 탭) — 스냅샷·
 * 댓글·팔로워 보강이 없는 별개 성격의 데이터라 같은 필터·정렬·counts 계약에 억지로 끼워 맞추면
 * null 필드가 늘어난다. {@link #hashtagPosts} 참조.
 */
@RestController
@RequestMapping("/v1/brand-monitoring")
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class V1BrandPostsController {

	/**
	 * 목록 상한(FE 명세 meta.limit) — 수집 개수 상한(monitoring {@code collection-post-limit:2000},
	 * 2026-08-19 스펙)과 같은 값으로, 수집하는 만큼 보여줄 수 있는 양이다. 구 200은 90일·105건
	 * 시절의 값이라 정책 v1(365일 윈도우·저장소 상한 폐지) 이후 12개월치가 많은 브랜드(실측
	 * 463건)를 실제로 잘랐다 — 잘린 것은 정렬 뒤쪽, 즉 새로 백필된 소급분이었다.
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
	private final BrandHashtagPostAssembler hashtagPostAssembler;
	private final Clock clock;

	public V1BrandPostsController(BrandLinkRepository linkRepository, BrandReadRepository brandReadRepository,
			BrandPostAssembler assembler, V1BrandDirectPostService directPostService,
			BrandHashtagPostAssembler hashtagPostAssembler, Clock clock) {
		this.linkRepository = linkRepository;
		this.brandReadRepository = brandReadRepository;
		this.assembler = assembler;
		this.directPostService = directPostService;
		this.hashtagPostAssembler = hashtagPostAssembler;
		this.clock = clock;
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
		BrandLinkRow link = requireOwnership(principal.getUserId(), brandId);
		BrandAccountRow account = findAccountOrThrow(brandId);

		String sourceFilter = normalizeFilter(source, "source", BrandPostAssembler.SOURCE_TAGGED,
				BrandPostAssembler.SOURCE_DIRECT);
		String sponsorshipFilter = normalizeFilter(sponsorship, "sponsorship", BrandSponsorshipClassifier.SPONSORED,
				BrandSponsorshipClassifier.ORGANIC, BrandSponsorshipClassifier.UNKNOWN);
		String sortKey = normalizeSort(sort);
		LocalDate from = parseDate(uploadedFrom, "uploadedFrom");
		LocalDate to = parseDate(uploadedTo, "uploadedTo");

		// 유저 표시 창(2026-08-17) — 자산(brand_account)은 유저 간 max로 수집하므로 12개월치가
		// 있어도, 이 유저가 신청한 기간까지만 서빙한다. counts·필터·정렬 전부 자른 전량 기준.
		// 컷은 스트림 밖에서 한 번만 구한다 — 건마다 시계를 읽으면 자정을 걸친 응답에서 창이 흔들린다.
		LocalDate windowStart = linkWindowStart(today(), link.collectionMonths());
		List<BrandPostResponse> all = assembler.assembleForBrand(principal.getUserId(), account).stream()
				.filter(p -> withinLinkWindow(p, windowStart))
				.toList();
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
	 * 해시태그 발견 게시물 전용 표면(스펙 §8, 별도 탭 결정 2026-08-12) — {@link #list}(tagged·direct)와
	 * 완전히 분리된 API다. 병합·필터·정렬·counts가 없다 — {@link BrandHashtagPostAssembler}가 최신순
	 * 전량(상한은 그쪽 정책)을 그대로 내려준다. 소유 검증은 목록과 같은 관용구(403·404).
	 */
	@GetMapping("/accounts/{accountId}/hashtag-posts")
	public ApiResponse<List<BrandHashtagPostResponse>> hashtagPosts(
			@AuthenticationPrincipal AppUserDetails principal, @PathVariable String accountId) {
		long brandId = parseAccountId(accountId);
		requireOwnership(principal.getUserId(), brandId);
		findAccountOrThrow(brandId);
		return ApiResponse.ok(hashtagPostAssembler.assembleForBrand(principal.getUserId(), brandId));
	}

	/**
	 * 상세(§6-2) — postId는 shortcode다. 경로에 브랜드가 없어 연결된 브랜드 전체(다계정)를 순서대로
	 * 뒤진다 — 어느 목록에도 없으면 존재 여부를 흘리지 않고 404.
	 */
	@GetMapping("/posts/{postId}")
	public ApiResponse<BrandPostResponse> get(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String postId) {
		// 시계는 요청당 한 번만 읽는다 — 브랜드마다 다시 읽으면 자정을 걸친 응답에서 브랜드별로 컷이 다르다.
		LocalDate today = today();
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(principal.getUserId())) {
			Optional<BrandAccountRow> account = brandReadRepository.findAccount(link.brandId());
			if (account.isEmpty()) {
				continue;
			}
			LocalDate windowStart = linkWindowStart(today, link.collectionMonths());
			Optional<BrandPostResponse> found = assembler.assembleForBrand(principal.getUserId(), account.get())
					.stream()
					.filter(p -> p.id().equals(postId))
					// 창 밖 게시물은 목록에 없다 — 상세만 열리는 불일치를 만들지 않는다(같은 404).
					// id 매칭 뒤에 둬서 창 판정(업로드일 파싱)은 후보 1건에만 돈다.
					.filter(p -> withinLinkWindow(p, windowStart))
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
	 * 성과 측정 취소(신규, FE 요청 2026-08-17) — postId는 {@link BrandPostResponse#id()}(=shortcode).
	 * 레거시 취소(POST /v1/monitoring/items/{itemId}/cancel)는 monitoringItemId 기준이라 shortcode만
	 * 아는 브랜드 화면에서는 호출할 수 없었다 — 이 엔드포인트가 그 표면을 메운다.
	 *
	 * <p>취소 성공 시(204) 게시물 목록({@code GET .../posts})에서 해당 행이 즉시 제거된다(ended로
	 * 남지 않는다) — 같은 URL을 다시 직접 등록하면 새 등록으로 처리된다(취소 후 재시작).
	 * direct 매핑이 없고 tagged 풀에 있는 shortcode는 400(TAGGED_POST_NOT_CANCELABLE) — tagged 행은
	 * 애초에 취소 대상이 아니다. 어느 쪽에도 없으면 404.
	 */
	@PostMapping("/posts/{postId}/cancel")
	public ResponseEntity<Void> cancelPost(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String postId) {
		directPostService.cancel(principal.getUserId(), postId);
		return ResponseEntity.noContent().build();
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

	/** 창 계산의 기준일 — KST 달력일(windowCutoff 관용구 동형: 인스턴트 빼기는 경계가 흔들린다). */
	private LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), KstTimestamps.KST);
	}

	/** 링크 표시 창의 하한. */
	private static LocalDate linkWindowStart(LocalDate today, int collectionMonths) {
		return today.minusMonths(collectionMonths);
	}

	/**
	 * 링크 창 판정(2026-08-17) — direct는 유저가 URL을 명시 등록한 추적 대상이라 창과 무관하게
	 * 통과한다(창은 태그 수집 범위의 개념). 나머지는 기간 필터와 같은 판정이라 그쪽에 위임한다
	 * (업로드일 미상 제외 규칙의 정의가 {@link #withinUploadWindow} 한 곳에만 있게).
	 */
	private static boolean withinLinkWindow(BrandPostResponse post, LocalDate windowStart) {
		return BrandPostAssembler.SOURCE_DIRECT.equals(post.source())
				|| withinUploadWindow(post, windowStart, null);
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

	private BrandLinkRow requireOwnership(long userId, long brandId) {
		return linkRepository.findActiveByUserAndBrand(userId, brandId)
				.orElseThrow(() -> V1ApiException.forbidden("FORBIDDEN", "브랜드 계정을 찾을 수 없거나 접근 권한이 없어요."));
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
