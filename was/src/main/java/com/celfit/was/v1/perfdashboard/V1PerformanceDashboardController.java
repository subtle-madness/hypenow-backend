package com.celfit.was.v1.perfdashboard;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.brandmonitoring.BrandAccountType;
import com.celfit.was.v1.brandmonitoring.BrandSponsorshipClassifier;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.monitoring.ItemStatus;
import com.celfit.was.v1.perfdashboard.PerformanceContentAssembler.DashboardRef;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 성과 대시보드 표면(스펙 §7-1) — 3계열(individual·direct·tagged) 통합 목록과 단건 조회. 인증 필수.
 *
 * <p>조립·중복 제거는 {@link PerformanceContentAssembler}가 끝낸다 — 이 컨트롤러는 HTTP 표면(쿼리
 * 값 공간 검증·필터·정렬·페이지 슬라이스·meta)만 담당한다. 필터·정렬은 전부 메모리이고, 목록은
 * <b>2단 조립</b>이다(2026-08-27): 경량 ref 인덱스 위에서 판정을 끝내고 응답에 실을 페이지만 카드로
 * 조립한다. 페이지 파라미터를 생략하면 전량이라 종전 응답과 같다(08-10 250건 상한 철폐 이후 계약).
 *
 * <p>브랜드 표면(§5·§6)과 달리 {@code monitoring.enabled} 조건부가 <b>아니다</b> — 대시보드는 브랜드
 * 연동 없는 유저(레거시 개인 추적만)도 쓰는 화면이고, 어셈블러가 monitoring 비활성 환경에서 브랜드
 * 계열을 건너뛰도록 이미 설계돼 있다.
 */
@RestController
@RequestMapping("/v1/performance-dashboard")
public class V1PerformanceDashboardController {

	private static final String FILTER_ALL = "all";
	/** campaignId 전용 값 — "캠페인에 묶이지 않은 콘텐츠만"(값이 아니라 부재를 고르는 필터라 별도 어휘). */
	private static final String CAMPAIGN_NONE = "none";

	/** 정렬 키 값 공간(2026-08-27 §2) — 기본값이자 종전 응답 순서인 uploaded가 첫 원소다. */
	private static final String SORT_UPLOADED = "uploaded";
	private static final String SORT_VIEWS = "views";
	private static final String SORT_LIKES = "likes";
	private static final String SORT_COMMENTS = "comments";
	private static final String SORT_ENGAGEMENT = "engagement";
	private static final List<String> SORT_KEYS =
			List.of(SORT_UPLOADED, SORT_VIEWS, SORT_LIKES, SORT_COMMENTS, SORT_ENGAGEMENT);

	private static final String ORDER_DESC = "desc";
	private static final String ORDER_ASC = "asc";

	/** 스냅샷 모드(2026-08-27 §3) — 기본은 전체 이력(full), latest는 최신 1개로 줄인 옵트인이다. */
	private static final String SNAPSHOT_MODE_FULL = "full";
	private static final String SNAPSHOT_MODE_LATEST = "latest";

	/** 페이지 크기 상한·기본값 — 브랜드 목록(PR #602)과 같은 캡이다. */
	private static final int PAGE_LIMIT_MAX = 100;
	private static final int PAGE_LIMIT_DEFAULT = 100;

	/**
	 * statusCounts 키 순서(FE 탭 순서) — 레거시 {@link ItemStatus} 어휘 그대로다. 브랜드 풀 합성
	 * 아이템도 hidden이 가능하다(2026-08-25 삭제 감지) — 어휘는 여전히 이 목록 안이다.
	 */
	private static final List<String> STATUSES = List.of(ItemStatus.TRACKING, ItemStatus.COLLECTING,
			ItemStatus.DETECTING, ItemStatus.NOT_UPLOADED, ItemStatus.ENDED, ItemStatus.HIDDEN, ItemStatus.ERROR);

	/** {@code status} 필터의 허용 값(가변인자 검증용) — 요청마다 배열을 만들지 않게 미리 굳힌다. */
	private static final String[] STATUS_VALUES = STATUSES.toArray(String[]::new);

	private final PerformanceContentAssembler assembler;
	private final PerformanceComparisonAssembler comparisonAssembler;

	public V1PerformanceDashboardController(PerformanceContentAssembler assembler,
			PerformanceComparisonAssembler comparisonAssembler) {
		this.assembler = assembler;
		this.comparisonAssembler = comparisonAssembler;
	}

	/**
	 * 통합 목록 — {@code data}는 전 필터 적용, {@code meta.statusCounts}는 <b>분류 필터</b>(출처·협찬·
	 * 캠페인·brandAccountId·accountType)만 적용한 모수에서 센다(스펙 §7-1, accountType은 08-12 추가).
	 *
	 * <p>statusCounts 모수에서 빠지는 필터는 둘이다:
	 * <ul>
	 *   <li><b>업로드 기간</b>({@code uploadedFrom}·{@code uploadedTo}) — 기간을 좁혀도 상태 뱃지가
	 *       흔들리지 않아야 한다(§7-1 명문).</li>
	 *   <li><b>{@code status} 자신</b> — statusCounts는 상태 축 뱃지라 자기 필터를 적용하면
	 *       {@code status=ended} 조회에서 나머지 6키가 전부 0이 되어 탭 바가 무력화된다. 형제
	 *       엔드포인트 §6-1의 {@code meta.counts}가 "필터 적용 전 전량"인 것과 같은 취지다.</li>
	 * </ul>
	 *
	 * <p>{@code sponsorship}은 <b>적용한다</b> — 카운트 키 축(상태)과 직교라 자기 0화가 없고,
	 * §7-1이 statusCounts에 적용한다고 본 "분류 범위" 필터에 속한다.
	 *
	 * <p>{@code authorUsername}(2026-08-27)도 <b>분류 필터</b>다 — 인플루언서 상세 뷰의 상태 뱃지는
	 * 그 작성자의 콘텐츠 기준이어야 하므로 statusCounts 모수에도 적용한다.
	 *
	 * <p><b>{@code brandAccountId}를 명시하면 {@code accountType=all}이 함의된다</b>(08-12 리뷰):
	 * 유저가 그 브랜드를 콕 집어 물었으므로 "경쟁사 제외" 기본값까지 겹쳐 걸면 안 된다. 겹쳐 걸면
	 * 경쟁사 브랜드를 지정한 조회가 오류도 힌트도 없이 빈 {@code data} + 전 상태 0이 되는데,
	 * 브랜드 칩은 경쟁사까지 내려주는 {@code /v1/brand-monitoring/accounts}로 만들고 §6의
	 * {@code /comparison}은 같은 계정의 막대를 정상적으로 그리므로 두 표면이 서로 어긋난다.
	 * <b>{@code accountType}을 명시하면 그쪽이 이긴다</b> — {@code brandAccountId=X&accountType=own}은
	 * 문자 그대로 "X가 경쟁사면 빈 결과"다(명시한 값의 의미를 함의가 덮지 않는다).
	 *
	 * <p><b>2단 조립</b>(2026-08-27 설계 §1) — 필터·statusCounts·정렬·페이지 슬라이스는 전부 경량
	 * ref({@link PerformanceContentAssembler#index}) 위에서 끝내고, 무거운 카드 조립(스냅샷 시계열·
	 * 표시 메타)은 <b>응답에 실을 페이지</b>만
	 * {@link PerformanceContentAssembler#hydratePage}로 만든다. 페이지 파라미터를 생략하면 전량
	 * 하이드레이트라 응답은 종전과 같다(하위 호환).
	 */
	@GetMapping("/contents")
	public ApiResponse<List<PerformanceContentResponse>> contents(
			@AuthenticationPrincipal AppUserDetails principal,
			@RequestParam(required = false) String uploadedFrom,
			@RequestParam(required = false) String uploadedTo,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) String sponsorship,
			@RequestParam(required = false) String campaignId,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String brandAccountId,
			@RequestParam(required = false) String accountIds,
			@RequestParam(required = false) String accountType,
			@RequestParam(required = false) String authorUsername,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String order,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Integer offset,
			@RequestParam(required = false) String snapshotMode) {
		String sourceFilter = normalizeFilter(source, "source", PerformanceContentAssembler.SOURCE_INDIVIDUAL,
				PerformanceContentAssembler.SOURCE_DIRECT, PerformanceContentAssembler.SOURCE_TAGGED);
		String sponsorshipFilter = normalizeFilter(sponsorship, "sponsorship", BrandSponsorshipClassifier.SPONSORED,
				BrandSponsorshipClassifier.ORGANIC, BrandSponsorshipClassifier.UNKNOWN);
		String statusFilter = normalizeFilter(status, "status", STATUS_VALUES);
		// campaignId는 all(필터 없음)·none(캠페인 없음)·캠페인 id 문자열 셋을 받는다 — none 판정은
		// matchesCampaign에서 한다.
		String campaignFilter = normalizeFilter(campaignId);
		String brandFilter = normalizeFilter(brandAccountId);
		Set<String> accountIdsFilter = normalizeAccountIds(accountIds);
		// 공용 normalizeFilter를 쓰지 않는다 — 이 파라미터만 미지정과 all이 다르다(아래 javadoc).
		// 함의 인자는 "브랜드를 콕 집어 물었는가"다 — 단수·복수 어느 쪽이든 accountType=all을 함의한다.
		String accountTypeFilter = normalizeAccountType(accountType,
				brandFilter != null || accountIdsFilter != null);
		String authorFilter = normalizeAuthorUsername(authorUsername);
		String sortKey = normalizeSort(sort);
		boolean ascending = normalizeOrder(order);
		boolean latestSnapshotOnly = normalizeSnapshotMode(snapshotMode);
		LocalDate from = parseDate(uploadedFrom, "uploadedFrom");
		LocalDate to = parseDate(uploadedTo, "uploadedTo");
		PageParams page = normalizePage(limit, offset);

		// 인덱스 패스(경량) — 여기부터 페이지 슬라이스까지 전부 ref 위에서 끝낸다.
		PerformanceContentAssembler.DashboardIndex index = assembler.index(principal.getUserId());
		Set<String> competitorIds = index.competitorBrandAccountIds();

		// 분류 필터 — statusCounts 모수의 술어다(status·업로드 기간은 여기 없다, 위 javadoc).
		Predicate<DashboardRef> classification = r ->
				(sourceFilter == null || sourceFilter.equals(r.source()))
						&& (sponsorshipFilter == null || sponsorshipFilter.equals(r.sponsorship()))
						&& matchesCampaign(r.campaignId(), campaignFilter)
						&& matchesBrand(r.brandAccountId(), brandFilter, accountIdsFilter)
						&& matchesAccountType(r.brandAccountId(), accountTypeFilter, competitorIds)
						&& (authorFilter == null || authorFilter.equalsIgnoreCase(r.handle()));

		// statusCounts 모수 — data는 여기서 status·기간을 더 걸어 갈라져 나온다(같은 모수 출신).
		List<DashboardRef> counted = index.refs().stream().filter(classification).toList();
		List<DashboardRef> filtered = counted.stream()
				.filter(r -> statusFilter == null || statusFilter.equals(r.status()))
				.filter(r -> withinUploadWindow(r.uploadedOn(), from, to))
				.sorted(comparator(sortKey, ascending))
				.toList();
		List<DashboardRef> pageRefs = page == null ? filtered
				: filtered.stream().skip(page.offset()).limit(page.limit()).toList();

		// 하이드레이트(무거움)는 응답에 실을 ref만 — 반환 순서는 pageRefs 순서 그대로다.
		List<PerformanceContentResponse> data = assembler.hydratePage(index, pageRefs);
		if (latestSnapshotOnly) {
			data = data.stream().map(PerformanceContentResponse::withLatestSnapshotOnly).toList();
		}
		return ApiResponse.ok(data, meta(filtered.size(), counted, index.lastCollectedAt(), page));
	}

	/**
	 * 단건 — {@code contentId}는 {@code canonicalPostId}(순수 shortcode)다. 내 소유 범위 밖이면 존재
	 * 여부를 흘리지 않고 404(§6-2 관용구와 동일).
	 *
	 * <p>같은 shortcode의 콘텐츠가 둘 이상일 수 있다 — 종결 후 재등록처럼 레거시 아이템이 두 행인
	 * 경우다(각자 캠페인·기간이 다른 별개 등록이라 어셈블러가 접지 않는다, Task 9 판단). 그때는
	 * <b>첫 매치</b>를 돌려준다: 어셈블러 정렬이 업로드 최신순·동률은 item id로 고정돼 있어 첫 매치가
	 * 요청마다 흔들리지 않고, 목록의 첫 등장 순서와도 일치한다.
	 *
	 * <p>단건만 <b>전체 조립</b>(댓글 포함)이다 — 목록·비교는 댓글 없는 경로(08-12 슬림 계약을 승계한
	 * 2단 조립)라 {@code recentComments}가 항상 빈 배열이고, 댓글이 필요한 소비자는 이 엔드포인트로
	 * 온다. 스냅샷도 항상 전체 이력이다({@code snapshotMode}는 목록 전용 파라미터, §3).
	 */
	@GetMapping("/contents/{contentId}")
	public ApiResponse<PerformanceContentResponse> content(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String contentId) {
		return assembler.assemble(principal.getUserId()).contents().stream()
				.filter(c -> contentId.equals(c.canonicalPostId()))
				.findFirst()
				.map(ApiResponse::ok)
				.orElseThrow(() -> V1ApiException.notFound("게시물을 찾을 수 없습니다."));
	}

	/**
	 * 성과 비교 집계(스펙 2026-08-10) — 브랜드 계정 × 5구간. 기간 파라미터는 없다(5구간 항상 전부).
	 * 모수는 목록과 같은 인덱스 전량에 분류 필터(source·sponsorship·campaignId)만 건 것 — 목록·비교
	 * 막대의 숫자가 정의상 일치한다. individual은 계정 귀속이 불가능해 집계에서 빠진다
	 * (source=individual이면 전 구간이 빈다 — 의도된 동작).
	 *
	 * <p>{@code accountType} 파라미터는 <b>없다</b>(08-12) — own·competitor를 나란히 놓는 것이 이
	 * 화면의 존재 이유라 타입으로 축을 걸러낼 여지가 없다. 계정별 타입은 응답 필드로 내린다(스펙 §6).
	 */
	@GetMapping("/comparison")
	public ApiResponse<PerformanceComparisonResponse> comparison(
			@AuthenticationPrincipal AppUserDetails principal,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) String sponsorship,
			@RequestParam(required = false) String campaignId) {
		String sourceFilter = normalizeFilter(source, "source", PerformanceContentAssembler.SOURCE_INDIVIDUAL,
				PerformanceContentAssembler.SOURCE_DIRECT, PerformanceContentAssembler.SOURCE_TAGGED);
		String sponsorshipFilter = normalizeFilter(sponsorship, "sponsorship", BrandSponsorshipClassifier.SPONSORED,
				BrandSponsorshipClassifier.ORGANIC, BrandSponsorshipClassifier.UNKNOWN);
		String campaignFilter = normalizeFilter(campaignId);

		// 인덱스 패스(2026-08-27) — 비교 집계는 업로드일·귀속 브랜드·최신 스냅샷 지표만 소비하고
		// 그 값은 전부 ref에 있다. 카드 조립(스냅샷 시계열·표시 메타)은 이 표면에 필요 없다.
		List<PerformanceContentAssembler.DashboardRef> filtered =
				assembler.index(principal.getUserId()).refs().stream()
						.filter(r -> (sourceFilter == null || sourceFilter.equals(r.source()))
								&& (sponsorshipFilter == null || sponsorshipFilter.equals(r.sponsorship()))
								&& matchesCampaign(r.campaignId(), campaignFilter))
						.toList();
		return ApiResponse.ok(comparisonAssembler.assemble(principal.getUserId(), filtered));
	}

	// ---------- meta ----------

	/**
	 * @param total 필터(분류·status·기간) 적용 후 <b>전체</b> 건수 — 페이지 건수가 아니다.
	 * @param counted 분류 필터만 적용한 모수 — statusCounts의 모수다(status·기간 미적용, §7-1).
	 * @param page 페이지 파라미터(null = 전량 모드)
	 */
	private static Map<String, Object> meta(int total, List<DashboardRef> counted,
			OffsetDateTime lastCollectedAt, PageParams page) {
		Map<String, Long> statusCounts = new LinkedHashMap<>();
		// 7종 키는 0건이어도 전부 존재해야 한다(FE 탭 뱃지가 키 부재를 다루지 않는다).
		STATUSES.forEach(s -> statusCounts.put(s, 0L));
		for (DashboardRef ref : counted) {
			// 방어적으로 merge다 — 값 공간 밖 상태가 생겨도 뭉개지 않고 키를 늘린다.
			statusCounts.merge(ref.status(), 1L, Long::sum);
		}

		// 페이지 정보는 meta.limit(형태 호환용 필드)과 분리한 additive 필드다 — 전량 응답이면
		// {offset: 0, limit: null}(limit null = 안 잘랐다는 표식, 키는 유지 — 계약 무결성 규칙 #1).
		Map<String, Object> pageMeta = new LinkedHashMap<>();
		pageMeta.put("offset", page == null ? 0 : page.offset());
		pageMeta.put("limit", page == null ? null : page.limit());

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", total);
		// 목록 상한 철폐(08-10) — 잘림이 없다. limit 키는 응답 형태 호환용으로 남기되 필터 후 전체
		// 건수와 같게 둔다(FE가 total > limit로 잘림을 판정해도 오탐이 없다). 페이지 크기는 meta.page다.
		meta.put("limit", total);
		meta.put("lastCollectedAt", KstTimestamps.toKstIso(lastCollectedAt));
		meta.put("statusCounts", statusCounts);
		meta.put("page", pageMeta);
		return meta;
	}

	// ---------- 정렬(2026-08-27 §2) ----------

	/**
	 * 정렬 비교자 — 정렬 키가 null인 콘텐츠는 {@code order}와 무관하게 <b>항상 마지막</b>이다
	 * (null은 "작은 값"이 아니라 "순위 밖"이라서다). 타이브레이크는 업로드 최신순 →
	 * {@code contentKey}(=item.id)라 전순서다 — 페이지 간 중복·누락이 생기지 않는다.
	 *
	 * <p>좋아요 숨김({@code latestLikesHidden})은 값이 아니라 미상이라 likes·engagement 키에서
	 * null로 접는다 — 0으로 두면 "좋아요가 없는 게시물"과 구분되지 않는다.
	 */
	private static Comparator<DashboardRef> comparator(String sortKey, boolean ascending) {
		if (SORT_UPLOADED.equals(sortKey)) {
			return Comparator.comparing(DashboardRef::uploadedOn, directional(ascending))
					.thenComparing(DashboardRef::contentKey);
		}
		// 타이브레이크는 uploaded 분기가 자체 순서를 갖는 나머지 키에서만 쓴다.
		Comparator<DashboardRef> tie = Comparator
				.comparing(DashboardRef::uploadedOn, Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(DashboardRef::contentKey);
		Function<DashboardRef, Double> key = switch (sortKey) {
			case SORT_VIEWS -> r -> r.latestViews() == null ? null : r.latestViews().doubleValue();
			case SORT_LIKES -> r -> r.latestLikesHidden() || r.latestLikes() == null ? null
					: r.latestLikes().doubleValue();
			case SORT_COMMENTS -> r -> r.latestComments() == null ? null : r.latestComments().doubleValue();
			default -> V1PerformanceDashboardController::engagementOf;   // SORT_ENGAGEMENT
		};
		return Comparator.comparing(key, V1PerformanceDashboardController.<Double>directional(ascending))
				.thenComparing(tie);
	}

	/** 요청 방향 비교자 — null은 {@code order}와 무관하게 항상 마지막이다(위 javadoc). */
	private static <T extends Comparable<? super T>> Comparator<T> directional(boolean ascending) {
		return Comparator.nullsLast(ascending ? Comparator.<T>naturalOrder() : Comparator.<T>reverseOrder());
	}

	/**
	 * 참여율 = (최신 likes + comments) ÷ 작성자 팔로워 — 분자·분모 어느 쪽이든 미상(좋아요 숨김
	 * 포함)이거나 팔로워가 0이면 순위에서 뺀다(null). 0으로 대체하면 미상 게시물이 "참여율 0"으로
	 * 줄 세워져 실제 최하위와 섞인다.
	 */
	private static Double engagementOf(DashboardRef ref) {
		if (ref.followers() == null || ref.followers() <= 0 || ref.latestLikesHidden()
				|| ref.latestLikes() == null || ref.latestComments() == null) {
			return null;
		}
		return (ref.latestLikes() + ref.latestComments()) / (double) ref.followers();
	}

	// ---------- 필터 ----------

	private static boolean withinUploadWindow(LocalDate uploadedOn, LocalDate from, LocalDate to) {
		if (from == null && to == null) {
			return true;
		}
		if (uploadedOn == null) {
			// 업로드일을 모르는 콘텐츠(collecting·detecting 등 post 없는 아이템)는 기간 판정이 불가라
			// data에서 제외한다 — 다만 statusCounts 모수에는 그대로 남는다(§7-1).
			return false;
		}
		return (from == null || !uploadedOn.isBefore(from)) && (to == null || !uploadedOn.isAfter(to));
	}

	/**
	 * 브랜드 범위 술어 — 복수 {@code accountIds}가 단수 {@code brandAccountId}를 이긴다(2026-08-27
	 * §2, 신규약 우선). 둘 다 없으면 브랜드 범위 제한이 없다.
	 */
	private static boolean matchesBrand(String brandAccountId, String brandFilter, Set<String> accountIdsFilter) {
		if (accountIdsFilter != null) {
			return accountIdsFilter.contains(brandAccountId);
		}
		return brandFilter == null || brandFilter.equals(brandAccountId);
	}

	/**
	 * 캠페인 필터 술어 정본 — 카드(목록)와 ref(비교)가 같은 규칙을 쓴다. {@code none}은 값이 아니라
	 * 부재를 고르는 어휘라 별도 분기다(위 {@link #CAMPAIGN_NONE} 참고).
	 */
	private static boolean matchesCampaign(String campaignId, String filter) {
		if (filter == null) {
			return true;
		}
		return CAMPAIGN_NONE.equals(filter) ? campaignId == null : Objects.equals(campaignId, filter);
	}

	/**
	 * accountType 필터(08-12) — {@code all}(=null)은 전량, {@code competitor}는 경쟁사 구독 소속만,
	 * <b>미지정·own은 "경쟁사만 제외"</b>다.
	 *
	 * <p>미지정이 "own 브랜드만"이 아닌 이유: 이 응답에는 브랜드에 귀속되지 않는 레거시 개인 추적
	 * 콘텐츠(brandAccountId null)가 섞여 있어, 문자 그대로 own만 남기면 경쟁사를 하나도 등록하지
	 * 않은 유저의 성과 요약 숫자까지 줄어든다. 요청서의 의도(경쟁사가 내 성과를 오염시키지 않게)는
	 * 경쟁사만 빼는 것으로 충족된다(스펙 §5).
	 */
	private static boolean matchesAccountType(String brandAccountId, String filter, Set<String> competitorIds) {
		boolean competitor = brandAccountId != null && competitorIds.contains(brandAccountId);
		if (BrandAccountType.COMPETITOR.equals(filter)) {
			return competitor;
		}
		if (filter == null) {
			return true;   // all — 미지정과 갈라지는 지점이다(normalizeAccountType 참고).
		}
		return !competitor;
	}

	/**
	 * accountType 전용 정규화 — 다른 필터와 달리 미지정과 {@code all}이 다르다(미지정은 경쟁사 제외가
	 * 기본, all은 전량). 그래서 공용 {@link #normalizeFilter(String, String, String...)}를 쓰지 않는다:
	 * 그쪽에 태우면 미지정이 곧 전량이 되어 경쟁사 콘텐츠가 기본 성과 요약을 오염시킨다.
	 *
	 * <p>단, <b>미지정이면서 브랜드를 집어 물은 조회</b>({@code brandAccountId} 또는 {@code accountIds}
	 * 명시)는 전량(all)이다 — 특정 브랜드를 지정한 요청에 경쟁사 제외 기본값까지 겹쳐 걸면 경쟁사
	 * 브랜드 조회가 조용히 빈다(08-12 리뷰, 2026-08-27 복수 accountIds로 확장).
	 *
	 * @param brandSpecified 브랜드를 집어 물었는가(brandAccountId 또는 accountIds 명시) — 함의 판정용
	 * @return null = 전량(all), {@code "own"} = 경쟁사 제외, {@code "competitor"} = 경쟁사만
	 */
	private static String normalizeAccountType(String raw, boolean brandSpecified) {
		if (raw == null || raw.isBlank()) {
			return brandSpecified ? null : BrandAccountType.OWN;
		}
		if (FILTER_ALL.equals(raw)) {
			return null;
		}
		if (!BrandAccountType.isValid(raw)) {
			throw V1ApiException.validation("accountType 값이 올바르지 않아요.");
		}
		return raw;
	}

	/** 미지정·{@code all}은 필터 없음(null), 그 외 값은 허용 목록 밖이면 400. */
	private static String normalizeFilter(String raw, String param, String... allowed) {
		String value = normalizeFilter(raw);
		if (value == null) {
			return null;
		}
		for (String candidate : allowed) {
			if (candidate.equals(value)) {
				return value;
			}
		}
		throw V1ApiException.validation(param + " 값이 올바르지 않아요.");
	}

	/**
	 * 값 공간이 열린 파라미터(brandAccountId)용 정규화 — 미지정·빈 값·{@code all}은 필터 없음.
	 * 브랜드 id는 숫자 문자열이라 {@code all}이 실제 id와 충돌할 수 없어, FE의 "전체" 탭이 그대로
	 * 넘어와도 전량으로 받아준다.
	 */
	private static String normalizeFilter(String raw) {
		return raw == null || raw.isBlank() || FILTER_ALL.equals(raw) ? null : raw;
	}

	/**
	 * 복수 브랜드 필터(2026-08-27 §2) — 쉼표 목록이다. 미지정·빈 값·{@code all}은 필터 없음(null,
	 * FE의 "전체" 탭이 그대로 넘어와도 전량), 빈 항목은 무시하고 전부 비면 필터 없음이다.
	 */
	private static Set<String> normalizeAccountIds(String raw) {
		if (raw == null || raw.isBlank() || FILTER_ALL.equals(raw)) {
			return null;
		}
		Set<String> ids = Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty())
				.collect(Collectors.toCollection(LinkedHashSet::new));
		return ids.isEmpty() ? null : ids;
	}

	/**
	 * 작성자 필터(2026-08-27 §2) — 미지정·빈 값은 필터 없음. 공용 {@link #normalizeFilter(String)}을
	 * 쓰지 않는 이유는 값 공간이 인스타그램 핸들이라 {@code all}이 실제 계정명과 충돌할 수 있어서다
	 * (핸들 {@code all}을 가진 작성자를 조회할 수 없게 만들지 않는다). 비교는 대소문자 무시다 —
	 * 대시보드 handle은 소문자 계약이지만 레거시 아이템의 handle은 등록 시 입력값 그대로다.
	 */
	private static String normalizeAuthorUsername(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		return raw.trim();
	}

	/** 정렬 키 — 미지정·빈 값은 기본값 {@code uploaded}(종전 응답 순서), 값 공간 밖은 400. */
	private static String normalizeSort(String raw) {
		if (raw == null || raw.isBlank()) {
			return SORT_UPLOADED;
		}
		if (!SORT_KEYS.contains(raw)) {
			throw V1ApiException.validation("sort 값이 올바르지 않아요.");
		}
		return raw;
	}

	/** 정렬 방향 — 미지정·빈 값은 {@code desc}. @return true면 오름차순 */
	private static boolean normalizeOrder(String raw) {
		if (raw == null || raw.isBlank() || ORDER_DESC.equals(raw)) {
			return false;
		}
		if (!ORDER_ASC.equals(raw)) {
			throw V1ApiException.validation("order 값이 올바르지 않아요.");
		}
		return true;
	}

	/** 스냅샷 모드 — 미지정·빈 값·{@code full}은 전체 이력(하위 호환). @return true면 최신 1개만 */
	private static boolean normalizeSnapshotMode(String raw) {
		if (raw == null || raw.isBlank() || SNAPSHOT_MODE_FULL.equals(raw)) {
			return false;
		}
		if (!SNAPSHOT_MODE_LATEST.equals(raw)) {
			throw V1ApiException.validation("snapshotMode 값이 올바르지 않아요.");
		}
		return true;
	}

	/**
	 * 페이지 파라미터 정규화(2026-08-27 §2, 브랜드 목록 PR #602 관용구) — 둘 다 생략이면 null(전량,
	 * 하위 호환). 하나라도 있으면 페이지 모드이고 나머지는 기본값(offset 0 · limit 100)이다.
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

	/** 정규화된 페이지 파라미터 — null이면 전량 모드다({@link #normalizePage} 참조). */
	private record PageParams(int offset, int limit) {
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
}
