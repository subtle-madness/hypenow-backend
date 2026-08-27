package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * <p>목록은 2단 조립이다(2026-08-27 타임아웃 해소 설계) — 경량 인덱스({@link
 * BrandPostAssembler#indexForBrand}: 스냅샷 시계열·댓글 없는 {@code PostRef}) 위에서 counts·창·
 * 필터·정렬·페이지 슬라이스를 전부 계산하고, 응답에 실을 코드만 풀 카드로 하이드레이트한다.
 * 판정 함수가 풀 조립과 동일해 counts는 전량 풀 조립 값과 정의상 일치한다. {@code meta.counts}는
 * 필터 적용 <b>전</b> 전량 기준이라 FE가 탭 뱃지를 그릴 때 자기 필터 때문에 숫자가 흔들리지 않는다.
 *
 * <p>필터는 서버가 전량을 보고 판정한다(2026-08-27 서버 필터·패싯) — FE가 페이지 슬라이스만 받고
 * 클라이언트에서 거르던 구조에선 필터 결과가 "현재 페이지 안에 있는 것"으로 잘렸다. 축 4종(source·
 * sponsorship·contentType·adRisk)과 축이 아닌 3종(follower·keyword·authorUsername)이 있고,
 * {@code meta.facets}는 각 축을 <b>자기만 해제</b>하고 센 칩 숫자다({@link #facets}). 기존
 * {@code meta.counts}(필터 전 전량·flat 6키)·{@code meta.total}(필터 후) 계약은 그대로다 —
 * 파라미터를 전부 생략하면 응답은 신설 키를 뺀 나머지가 종전과 완전히 같다(하위 호환).
 *
 * <p>페이지네이션(FE 요청 2026-08-27)은 리더보드(6.1)의 offset/limit 계약을 복제하되, 이 표면의
 * {@code meta.limit}은 이미 "수집 상한"(2000) 의미로 쓰이고 있어 페이지 정보는 additive 신설 필드
 * {@code meta.page = {offset, limit}}로 분리한다(전량 응답이면 {@code {0, null}}). 파라미터를 둘 다
 * 생략하면 기존 전량 응답 그대로다(하위 호환). 목록 항목은 댓글을 싣지 않는다(FE 요청 1 —
 * {@code recentComments: []}, 상세만 포함).
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

	/** 페이지 크기 상한·기본값(FE 요청 2026-08-27) — 리더보드(6.1 V1ContentQuery)와 같은 캡. */
	private static final int PAGE_LIMIT_MAX = 100;
	private static final int PAGE_LIMIT_DEFAULT = 100;

	private static final String FILTER_ALL = "all";
	/** 매체 어휘는 카드 {@code contentType}과 같은 값이다(불명은 피드로 접힌 뒤 ref에 실린다). */
	private static final String CONTENT_TYPE_REELS = "reels";
	private static final String CONTENT_TYPE_FEED = "feed";
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
			@RequestParam(required = false) String contentType,
			@RequestParam(required = false) String follower,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String adRisk,
			@RequestParam(required = false) String authorUsername,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String uploadedFrom,
			@RequestParam(required = false) String uploadedTo,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Integer offset) {
		long brandId = parseAccountId(accountId);
		BrandLinkRow link = requireOwnership(principal.getUserId(), brandId);
		BrandAccountRow account = findAccountOrThrow(brandId);

		String sourceFilter = normalizeFilter(source, "source", BrandPostAssembler.SOURCE_TAGGED,
				BrandPostAssembler.SOURCE_DIRECT);
		String sponsorshipFilter = normalizeFilter(sponsorship, "sponsorship", BrandSponsorshipClassifier.SPONSORED,
				BrandSponsorshipClassifier.ORGANIC, BrandSponsorshipClassifier.UNKNOWN);
		String contentTypeFilter = normalizeFilter(contentType, "contentType", CONTENT_TYPE_REELS, CONTENT_TYPE_FEED);
		String sortKey = normalizeSort(sort);
		LocalDate from = parseDate(uploadedFrom, "uploadedFrom");
		LocalDate to = parseDate(uploadedTo, "uploadedTo");
		PageParams page = normalizePage(limit, offset);
		// 광고 표기 노출 게이트는 요청당 1회만 계산한다(조회자 관점 — 토글 && 비경쟁사).
		PostFilters filters = new PostFilters(sourceFilter, sponsorshipFilter, contentTypeFilter,
				parseFollower(follower), normalizeText(keyword, true), normalizeText(authorUsername, false),
				parseAdRisk(adRisk), assembler.adDisclosureExposed(link.accountType()), from, to);

		// 유저 표시 창(2026-08-17) — 자산(brand_account)은 유저 간 max로 수집하므로 12개월치가
		// 있어도, 이 유저가 신청한 기간까지만 서빙한다. counts·필터·정렬 전부 자른 전량 기준.
		// 컷은 스트림 밖에서 한 번만 구한다 — 건마다 시계를 읽으면 자정을 걸친 응답에서 창이 흔들린다.
		LocalDate windowStart = BrandPostWindows.linkWindowStart(today(), link.collectionMonths());
		// 인덱스 패스(경량) — counts·필터·정렬·페이지 슬라이스는 전부 ref 위에서 끝낸다. 최신뷰
		// 정렬 키는 performance 정렬일 때만 조회한다(그 외 정렬에선 스냅샷을 아예 안 읽는다).
		BrandPostAssembler.BrandPostIndex index = assembler.indexForBrand(principal.getUserId(), account,
				SORT_PERFORMANCE_DESC.equals(sortKey));
		List<BrandPostAssembler.PostRef> all = index.refs().stream()
				.filter(r -> BrandPostWindows.withinLinkWindow(r, windowStart))
				.toList();
		List<BrandPostAssembler.PostRef> filtered = applyFilters(all, filters, FacetAxis.NONE).stream()
				.sorted(comparator(sortKey))
				.limit(POST_LIMIT)
				.toList();
		List<BrandPostAssembler.PostRef> pageRefs = page == null ? filtered
				: filtered.stream().skip(page.offset()).limit(page.limit()).toList();

		// 하이드레이트(무거움)는 응답에 실을 코드만 — 목록은 댓글을 싣지 않는다(FE 요청 1).
		List<BrandPostResponse> body = assembler.hydrate(principal.getUserId(), account, link.accountType(),
				index, pageRefs.stream().map(BrandPostAssembler.PostRef::shortcode).toList(), false);
		return ApiResponse.ok(body, meta(filtered.size(), all, account, page, filters));
	}

	/**
	 * 페이지 파라미터 정규화(FE 요청 2026-08-27) — 둘 다 생략이면 null(전량, 하위 호환). 하나라도
	 * 있으면 페이지 모드: limit 기본 100(1..100, 리더보드 캡 복제)·offset 기본 0(≥0). 범위 밖 400.
	 */
	private static PageParams normalizePage(Integer limit, Integer offset) {
		if (limit == null && offset == null) {
			return null;
		}
		int lim = limit == null ? PAGE_LIMIT_DEFAULT : limit;
		if (lim < 1 || lim > PAGE_LIMIT_MAX) {
			throw V1ApiException.validation("limit은 1~" + PAGE_LIMIT_MAX + " 사이여야 해요.");
		}
		int off = offset == null ? 0 : offset;
		if (off < 0) {
			throw V1ApiException.validation("offset은 0 이상이어야 해요.");
		}
		return new PageParams(off, lim);
	}

	/** 정규화된 페이지 파라미터 — null이면 전량 모드다(normalizePage 참조). */
	private record PageParams(int offset, int limit) {
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
	 * 해시태그 발견 게시물 개수만(P2, 2026-08-27) — FE 탭 뱃지가 목록 본문 없이 숫자만 필요할 때 쓴다
	 * (전량 조립·전송을 태우지 않는 슬림 경로). 판정은 {@link #hashtagPosts}와 완전히 같은 함수를
	 * 공유하므로({@link BrandHashtagPostAssembler#countForBrand}) 이 숫자는 정의상 목록 길이와 같다.
	 * 소유 검증도 목록과 같은 관용구(403·404)다.
	 */
	@GetMapping("/accounts/{accountId}/hashtag-posts/count")
	public ApiResponse<Map<String, Object>> hashtagPostCount(
			@AuthenticationPrincipal AppUserDetails principal, @PathVariable String accountId) {
		long brandId = parseAccountId(accountId);
		requireOwnership(principal.getUserId(), brandId);
		findAccountOrThrow(brandId);
		return ApiResponse.ok(Map.of("count", hashtagPostAssembler.countForBrand(principal.getUserId(), brandId)));
	}

	/**
	 * 상세(§6-2) — postId는 shortcode다. 경로에 브랜드가 없어 연결된 브랜드 전체(다계정)를 순서대로
	 * 뒤진다 — 어느 목록에도 없으면 존재 여부를 흘리지 않고 404.
	 *
	 * <p>목록과 같은 인덱스 기계를 탄다(2026-08-27) — 브랜드마다 경량 ref에서 shortcode·창을 판정하고,
	 * 일치한 그 1건만 풀 카드로 하이드레이트한다(브랜드 전량 풀 조립이던 구 경로의 비용 제거).
	 * 상세는 목록과 달리 댓글을 포함한다(FE 요청 1 — 댓글은 상세 전용).
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
			LocalDate windowStart = BrandPostWindows.linkWindowStart(today, link.collectionMonths());
			BrandPostAssembler.BrandPostIndex index = assembler.indexForBrand(principal.getUserId(),
					account.get(), false);
			// 창 밖 게시물은 목록에 없다 — 상세만 열리는 불일치를 만들지 않는다(같은 404).
			boolean present = index.refs().stream()
					.anyMatch(r -> r.shortcode().equals(postId) && BrandPostWindows.withinLinkWindow(r, windowStart));
			if (!present) {
				continue;
			}
			List<BrandPostResponse> found = assembler.hydrate(principal.getUserId(), account.get(),
					link.accountType(), index, List.of(postId), true);
			if (!found.isEmpty()) {
				return ApiResponse.ok(found.get(0));
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

	/** counts·total의 산지는 경량 ref다(2026-08-27) — 판정 함수가 풀 조립과 같아 전량 계산 값과 일치한다. */
	private static Map<String, Object> meta(int total, List<BrandPostAssembler.PostRef> all,
			BrandAccountRow account, PageParams page, PostFilters f) {
		Map<String, Object> counts = new LinkedHashMap<>();
		counts.put(FILTER_ALL, all.size());
		counts.put(BrandPostAssembler.SOURCE_TAGGED, count(all, BrandPostAssembler.PostRef::source,
				BrandPostAssembler.SOURCE_TAGGED));
		counts.put(BrandPostAssembler.SOURCE_DIRECT, count(all, BrandPostAssembler.PostRef::source,
				BrandPostAssembler.SOURCE_DIRECT));
		counts.put(BrandSponsorshipClassifier.SPONSORED, count(all, BrandPostAssembler.PostRef::sponsorship,
				BrandSponsorshipClassifier.SPONSORED));
		counts.put(BrandSponsorshipClassifier.ORGANIC, count(all, BrandPostAssembler.PostRef::sponsorship,
				BrandSponsorshipClassifier.ORGANIC));
		counts.put(BrandSponsorshipClassifier.UNKNOWN, count(all, BrandPostAssembler.PostRef::sponsorship,
				BrandSponsorshipClassifier.UNKNOWN));

		// 페이지 정보는 meta.limit(수집 상한 의미 선점)과 분리한 additive 필드다 — 전량 응답이면
		// {offset: 0, limit: null}(limit null = 안 잘랐다는 표식, 키는 유지 — 계약 무결성 규칙 #1).
		Map<String, Object> pageMeta = new LinkedHashMap<>();
		pageMeta.put("offset", page == null ? 0 : page.offset());
		pageMeta.put("limit", page == null ? null : page.limit());

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", total);
		meta.put("limit", POST_LIMIT);
		meta.put("page", pageMeta);
		meta.put("counts", counts);
		meta.put("facets", facets(all, f));
		meta.put("influencerCount", influencerCount(all, f));
		// 브랜드 화면의 "마지막 수집"은 브랜드 스윕 시각이다(직접 등록분의 레거시 스윕과 별개).
		meta.put("lastCollectedAt", KstTimestamps.toKstIso(account.lastSweptAt()));
		return meta;
	}

	private static long count(List<BrandPostAssembler.PostRef> all,
			Function<BrandPostAssembler.PostRef, String> field, String value) {
		return all.stream().filter(p -> value.equals(field.apply(p))).count();
	}

	// ---------- 패싯(2026-08-27 서버 필터·패싯 설계) ----------

	/**
	 * 필터 칩 숫자(FE 4축) — 각 축은 <b>그 축만 해제</b>하고 나머지 필터(기간 포함)를 전부 적용한 수다.
	 * 자기 축까지 적용하면 칩을 누르는 순간 다른 값들이 0으로 죽어 되돌릴 수 없는 UI가 된다.
	 * {@code meta.counts}(필터 전 전량·6키 flat)와는 다른 값이고, 그쪽 계약은 하위 호환으로 불변이다.
	 */
	private static Map<String, Object> facets(List<BrandPostAssembler.PostRef> all, PostFilters f) {
		Map<String, Object> facets = new LinkedHashMap<>();
		facets.put("contentType", axisMap(applyFilters(all, f, FacetAxis.CONTENT_TYPE),
				BrandPostAssembler.PostRef::contentType, CONTENT_TYPE_REELS, CONTENT_TYPE_FEED));
		facets.put("sponsorship", axisMap(applyFilters(all, f, FacetAxis.SPONSORSHIP),
				BrandPostAssembler.PostRef::sponsorship, BrandSponsorshipClassifier.SPONSORED,
				BrandSponsorshipClassifier.ORGANIC, BrandSponsorshipClassifier.UNKNOWN));
		facets.put("source", axisMap(applyFilters(all, f, FacetAxis.SOURCE),
				BrandPostAssembler.PostRef::source, BrandPostAssembler.SOURCE_TAGGED,
				BrandPostAssembler.SOURCE_DIRECT));
		// adRisk는 값이 아니라 불리언 축이라 맵이 아니라 "위험 건수" 하나다.
		facets.put("adRisk", applyFilters(all, f, FacetAxis.AD_RISK).stream()
				.filter(r -> isAdRisk(r, f.adGateOpen())).count());
		return facets;
	}

	/**
	 * 축 하나의 값별 건수 — {@code {"all": 총계, 값별 건수}}. 전 키를 0으로 선초기화한다(FE 키 부재
	 * 방어 관용구): 값이 0건이라 키가 사라지면 FE가 undefined를 렌더한다.
	 */
	private static Map<String, Long> axisMap(List<BrandPostAssembler.PostRef> refs,
			Function<BrandPostAssembler.PostRef, String> field, String... values) {
		Map<String, Long> map = new LinkedHashMap<>();
		map.put(FILTER_ALL, (long) refs.size());
		for (String value : values) {
			map.put(value, 0L);
		}
		for (BrandPostAssembler.PostRef ref : refs) {
			String value = field.apply(ref);
			if (value != null) {
				map.computeIfPresent(value, (key, prev) -> prev + 1);
			}
		}
		return map;
	}

	/**
	 * 인플루언서 수(FE 요약 카드) — 창 + 기간만 적용한 고유 작성자 수다. 필터 축을 적용하지 않는 이유는
	 * 이 숫자가 "이 기간에 이 브랜드를 올린 사람 수"라는 요약값이라서다(필터를 따라 움직이면 요약이
	 * 아니라 또 하나의 필터 결과가 된다). 작성자 미상(수집 전 직접 등록분)은 셀 근거가 없어 제외한다.
	 */
	private static long influencerCount(List<BrandPostAssembler.PostRef> all, PostFilters f) {
		return all.stream()
				.filter(r -> BrandPostWindows.withinUploadWindow(r.uploadedOn(), f.from(), f.to()))
				.map(BrandPostAssembler.PostRef::authorUsername)
				.filter(Objects::nonNull)
				.distinct()
				.count();
	}

	// ---------- 필터·정렬 ----------

	/**
	 * 정규화된 필터 묶음 — keyword는 trim·소문자 선처리(빈 문자열은 null), adGateOpen은 조회자의 광고
	 * 표기 노출 게이트 결과다({@link BrandPostAssembler#adDisclosureExposed} 1회 계산).
	 */
	private record PostFilters(String source, String sponsorship, String contentType, FollowerBand follower,
			String keyword, String authorUsername, boolean adRisk, boolean adGateOpen, LocalDate from,
			LocalDate to) {
	}

	/**
	 * 패싯 축 — 패싯이 "그 축만 해제"를 계산할 때 쓴다. 축이 아닌 필터(기간·keyword·follower·
	 * authorUsername)는 항상 적용된다(FE 칩 정의 — 칩은 4축뿐이다).
	 */
	private enum FacetAxis { SOURCE, SPONSORSHIP, CONTENT_TYPE, AD_RISK, NONE }

	private static List<BrandPostAssembler.PostRef> applyFilters(List<BrandPostAssembler.PostRef> refs,
			PostFilters f, FacetAxis released) {
		return refs.stream()
				.filter(r -> released == FacetAxis.SOURCE || f.source() == null
						|| f.source().equals(r.source()))
				.filter(r -> released == FacetAxis.SPONSORSHIP || f.sponsorship() == null
						|| f.sponsorship().equals(r.sponsorship()))
				.filter(r -> released == FacetAxis.CONTENT_TYPE || f.contentType() == null
						|| f.contentType().equals(r.contentType()))
				.filter(r -> released == FacetAxis.AD_RISK || !f.adRisk() || isAdRisk(r, f.adGateOpen()))
				.filter(r -> f.follower() == null || matchesFollower(r.authorFollowers(), f.follower()))
				.filter(r -> f.keyword() == null || matchesKeyword(r, f.keyword()))
				.filter(r -> f.authorUsername() == null
						|| f.authorUsername().equalsIgnoreCase(r.authorUsername()))
				.filter(r -> BrandPostWindows.withinUploadWindow(r.uploadedOn(), f.from(), f.to()))
				.toList();
	}

	/** 광고 표기 위험 판정 verdict 2종 — 인플루언서 집계(§6-6)도 같은 정의를 쓴다. */
	static final Set<String> AD_RISK_VERDICTS = Set.of("NOT_DISCLOSED", "INSUFFICIENT");

	/**
	 * adRisk 판정 = FE {@code hasAdDisclosureIssue} 복제 — 협찬 선행 조건 + verdict 2종 + 노출 게이트.
	 * verdict null 가드가 따로 있는 이유: 카드의 노출 게이트는 판정 메타가 있을 때만 필드를 채우는데
	 * {@link BrandPostAssembler#adDisclosureExposed}는 그 "메타 있음" 항을 포함하지 않는다 —
	 * 미판정(과도기 폴백 direct 등)을 위험으로 세면 화면에 없는 배지가 숫자로만 생긴다.
	 */
	private static boolean isAdRisk(BrandPostAssembler.PostRef r, boolean adGateOpen) {
		return adGateOpen && BrandSponsorshipClassifier.SPONSORED.equals(r.sponsorship())
				&& r.adVerdict() != null && AD_RISK_VERDICTS.contains(r.adVerdict());
	}

	private static boolean matchesKeyword(BrandPostAssembler.PostRef r, String keywordLower) {
		return (r.authorUsername() != null
						&& r.authorUsername().toLowerCase(Locale.ROOT).contains(keywordLower))
				|| (r.authorFullName() != null
						&& r.authorFullName().toLowerCase(Locale.ROOT).contains(keywordLower));
	}

	private static boolean matchesFollower(Long followers, FollowerBand band) {
		// 팔로워 미상은 어느 구간에도 넣지 않는다 — 0으로 접으면 "0-3k"가 미상 계정 창고가 된다.
		return followers != null && followers >= band.min()
				&& (band.max() == null || followers < band.max());
	}

	/** 팔로워 구간 — 하한 포함·상한 미포함. max null은 상한 없음(50k+). */
	record FollowerBand(long min, Long max) {
	}

	/**
	 * 팔로워 구간 토큰 5종(FE {@code BRAND_FOLLOWER_RANGES}와 같은 경계) — 미지정·{@code all}은
	 * 필터 없음(null), 그 외 값은 400. 인플루언서 집계(§6-6)가 같은 파서를 재사용한다.
	 */
	static FollowerBand parseFollower(String raw) {
		if (raw == null || raw.isBlank() || FILTER_ALL.equals(raw)) {
			return null;
		}
		return switch (raw) {
			case "0-3k" -> new FollowerBand(0L, 3_000L);
			case "3k-10k" -> new FollowerBand(3_000L, 10_000L);
			case "10k-30k" -> new FollowerBand(10_000L, 30_000L);
			case "30k-50k" -> new FollowerBand(30_000L, 50_000L);
			case "50k+" -> new FollowerBand(50_000L, null);
			default -> throw V1ApiException.validation("follower 값이 올바르지 않아요.");
		};
	}

	/** adRisk는 불리언 축이라 {@code true}/{@code false}만 받는다 — 미지정·빈 값은 필터 없음. */
	private static boolean parseAdRisk(String raw) {
		if (raw == null || raw.isBlank()) {
			return false;
		}
		if ("true".equals(raw) || "false".equals(raw)) {
			return "true".equals(raw);
		}
		throw V1ApiException.validation("adRisk 값이 올바르지 않아요.");
	}

	/** 자유 입력 정규화 — trim 후 빈 문자열은 필터 없음(null). keyword만 소문자로 접는다. */
	private static String normalizeText(String raw, boolean toLower) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String trimmed = raw.trim();
		return toLower ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
	}

	// 창 판정 3함수(withinUploadWindow·linkWindowStart·withinLinkWindow)는 BrandPostWindows로 옮겼다
	// (2026-08-27 서버 필터·패싯 설계) — 서버 필터·패싯이 같은 규칙을 재사용해야 하기 때문이다.

	/** 창 계산의 기준일 — KST 달력일(windowCutoff 관용구 동형: 인스턴트 빼기는 경계가 흔들린다). */
	private LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), KstTimestamps.KST);
	}

	/**
	 * performance_desc = 최신 스냅샷 views 내림차순, null(미수집·피드) 마지막. 동률·null 구간은
	 * 업로드 최신순으로 다시 정렬해 순서가 요청마다 흔들리지 않게 한다. shortcode 최종
	 * 타이브레이크까지 전순서라 페이지 간 중복·누락이 없다.
	 */
	private static Comparator<BrandPostAssembler.PostRef> comparator(String sortKey) {
		Comparator<BrandPostAssembler.PostRef> uploadedDesc = Comparator
				.comparing(BrandPostAssembler.PostRef::uploadedOn,
						Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(BrandPostAssembler.PostRef::shortcode);
		if (!SORT_PERFORMANCE_DESC.equals(sortKey)) {
			return uploadedDesc;
		}
		return Comparator.comparing(BrandPostAssembler.PostRef::latestViews,
				Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(uploadedDesc);
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
