package com.celfit.was.v1.perfdashboard;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.brandmonitoring.BrandSponsorshipClassifier;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.monitoring.ItemStatus;
import com.celfit.was.v1.perfdashboard.DashboardQueries.PageParams;
import com.celfit.was.v1.perfdashboard.PerformanceContentAssembler.DashboardRef;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
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

	/** 정렬 키 값 공간(2026-08-27 §2) — 기본값이자 종전 응답 순서인 uploaded가 첫 원소다. */
	private static final String SORT_UPLOADED = "uploaded";
	private static final String SORT_VIEWS = "views";
	private static final String SORT_LIKES = "likes";
	private static final String SORT_COMMENTS = "comments";
	private static final String SORT_ENGAGEMENT = "engagement";
	private static final List<String> SORT_KEYS =
			List.of(SORT_UPLOADED, SORT_VIEWS, SORT_LIKES, SORT_COMMENTS, SORT_ENGAGEMENT);

	private static final String SORT_POSTS = "posts";
	private static final String SORT_LATEST = "latest";

	/**
	 * 인플루언서 표면 전용 정렬 키(2026-08-27 §4) — 값 공간이 목록과 다르다: 집계 행엔 업로드일 축이
	 * 없어 {@code uploaded} 대신 {@code latest}(최신 업로드일)·{@code posts}(게시물 수)가 들어간다.
	 * 기본값은 첫 원소 {@code views}(FE 기본 화면이 조회수 랭킹이다).
	 */
	private static final List<String> INFLUENCER_SORT_KEYS =
			List.of(SORT_VIEWS, SORT_LIKES, SORT_COMMENTS, SORT_ENGAGEMENT, SORT_POSTS, SORT_LATEST);

	private static final String ORDER_DESC = "desc";
	private static final String ORDER_ASC = "asc";

	/** 스냅샷 모드(2026-08-27 §3) — 기본은 전체 이력(full), latest는 최신 1개로 줄인 옵트인이다. */
	private static final String SNAPSHOT_MODE_FULL = "full";
	private static final String SNAPSHOT_MODE_LATEST = "latest";

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
		String sourceFilter = DashboardQueries.normalizeFilter(source, "source",
				PerformanceContentAssembler.SOURCE_INDIVIDUAL, PerformanceContentAssembler.SOURCE_DIRECT,
				PerformanceContentAssembler.SOURCE_TAGGED);
		String sponsorshipFilter = DashboardQueries.normalizeFilter(sponsorship, "sponsorship",
				BrandSponsorshipClassifier.SPONSORED, BrandSponsorshipClassifier.ORGANIC,
				BrandSponsorshipClassifier.UNKNOWN);
		String statusFilter = DashboardQueries.normalizeFilter(status, "status", STATUS_VALUES);
		// campaignId는 all(필터 없음)·none(캠페인 없음)·캠페인 id 문자열 셋을 받는다 — none 판정은
		// matchesCampaign에서 한다.
		String campaignFilter = DashboardQueries.normalizeFilter(campaignId);
		String brandFilter = DashboardQueries.normalizeFilter(brandAccountId);
		Set<String> accountIdsFilter = DashboardQueries.normalizeAccountIds(accountIds);
		// 공용 normalizeFilter를 쓰지 않는다 — 이 파라미터만 미지정과 all이 다르다(DashboardQueries javadoc).
		// 함의 인자는 "브랜드를 콕 집어 물었는가"다 — 단수·복수 어느 쪽이든 accountType=all을 함의한다.
		String accountTypeFilter = DashboardQueries.normalizeAccountType(accountType,
				brandFilter != null || accountIdsFilter != null);
		String authorFilter = DashboardQueries.normalizeAuthorUsername(authorUsername);
		String sortKey = normalizeSort(sort);
		boolean ascending = normalizeOrder(order);
		boolean latestSnapshotOnly = normalizeSnapshotMode(snapshotMode);
		LocalDate from = DashboardQueries.parseDate(uploadedFrom, "uploadedFrom");
		LocalDate to = DashboardQueries.parseDate(uploadedTo, "uploadedTo");
		PageParams page = DashboardQueries.normalizePage(limit, offset);

		// 인덱스 패스(경량) — 여기부터 페이지 슬라이스까지 전부 ref 위에서 끝낸다.
		PerformanceContentAssembler.DashboardIndex index = assembler.index(principal.getUserId());
		Set<String> competitorIds = index.competitorBrandAccountIds();

		// 분류 필터 — statusCounts 모수의 술어다(status·업로드 기간은 여기 없다, 위 javadoc).
		Predicate<DashboardRef> classification = r ->
				(sourceFilter == null || sourceFilter.equals(r.source()))
						&& (sponsorshipFilter == null || sponsorshipFilter.equals(r.sponsorship()))
						&& DashboardQueries.matchesCampaign(r.campaignId(), campaignFilter)
						&& DashboardQueries.matchesBrand(r.brandAccountId(), brandFilter, accountIdsFilter)
						&& DashboardQueries.matchesAccountType(r.brandAccountId(), accountTypeFilter, competitorIds)
						&& (authorFilter == null || authorFilter.equalsIgnoreCase(r.handle()));

		// statusCounts 모수 — data는 여기서 status·기간을 더 걸어 갈라져 나온다(같은 모수 출신).
		List<DashboardRef> counted = index.refs().stream().filter(classification).toList();
		List<DashboardRef> filtered = counted.stream()
				.filter(r -> statusFilter == null || statusFilter.equals(r.status()))
				.filter(r -> DashboardQueries.withinUploadWindow(r.uploadedOn(), from, to))
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
		String sourceFilter = DashboardQueries.normalizeFilter(source, "source",
				PerformanceContentAssembler.SOURCE_INDIVIDUAL, PerformanceContentAssembler.SOURCE_DIRECT,
				PerformanceContentAssembler.SOURCE_TAGGED);
		String sponsorshipFilter = DashboardQueries.normalizeFilter(sponsorship, "sponsorship",
				BrandSponsorshipClassifier.SPONSORED, BrandSponsorshipClassifier.ORGANIC,
				BrandSponsorshipClassifier.UNKNOWN);
		String campaignFilter = DashboardQueries.normalizeFilter(campaignId);

		// 인덱스 패스(2026-08-27) — 비교 집계는 업로드일·귀속 브랜드·최신 스냅샷 지표만 소비하고
		// 그 값은 전부 ref에 있다. 카드 조립(스냅샷 시계열·표시 메타)은 이 표면에 필요 없다.
		List<PerformanceContentAssembler.DashboardRef> filtered =
				assembler.index(principal.getUserId()).refs().stream()
						.filter(r -> (sourceFilter == null || sourceFilter.equals(r.source()))
								&& (sponsorshipFilter == null || sponsorshipFilter.equals(r.sponsorship()))
								&& DashboardQueries.matchesCampaign(r.campaignId(), campaignFilter))
						.toList();
		return ApiResponse.ok(comparisonAssembler.assemble(principal.getUserId(), filtered));
	}

	/**
	 * 인기 인플루언서 집계(스펙 2026-08-27 §4) — 목록과 같은 인덱스 ref를 작성자(handle)로 접는다.
	 * DB 신규 쿼리는 없다(집계기는 순수 함수, {@link PerformanceInfluencerAggregator}).
	 *
	 * <p><b>필터는 전부 집계 모수에 적용된다</b> — 목록의 statusCounts처럼 "필터를 일부만 건 모수"가
	 * 이 표면엔 없다. 랭킹 행 자체가 응답의 전부라, 필터가 걸리면 행도 {@code meta.total}도 같이 준다.
	 *
	 * <p><b>단수 {@code brandAccountId} 파라미터는 없다</b> — 신설 표면이라 구계약(08-12 단수 파라미터)
	 * 짐을 지지 않는다. 브랜드 범위는 복수 {@code accountIds}가 정본이고, {@code accountType} 함의
	 * (명시하면 all, 단 명시한 accountType이 이김)는 목록과 같은 규칙이다.
	 *
	 * <p>정렬은 <b>집계 후 응답 행</b> 기준이다(ref 기준인 목록과 다르다) — 합계·비율이 접힌 뒤라야
	 * 나오는 값이라서다. 값 공간·기본값은 {@link #INFLUENCER_SORT_KEYS} 참고. 페이지는 정렬 후
	 * 슬라이스이고 {@code meta.total}은 슬라이스 전 집계 행 전체 수다.
	 */
	@GetMapping("/influencers")
	public ApiResponse<List<PerformanceInfluencerResponse>> influencers(
			@AuthenticationPrincipal AppUserDetails principal,
			@RequestParam(required = false) String uploadedFrom,
			@RequestParam(required = false) String uploadedTo,
			@RequestParam(required = false) String sponsorship,
			@RequestParam(required = false) String campaignId,
			@RequestParam(required = false) String accountIds,
			@RequestParam(required = false) String accountType,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String order,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Integer offset) {
		String sponsorshipFilter = DashboardQueries.normalizeFilter(sponsorship, "sponsorship",
				BrandSponsorshipClassifier.SPONSORED, BrandSponsorshipClassifier.ORGANIC,
				BrandSponsorshipClassifier.UNKNOWN);
		String campaignFilter = DashboardQueries.normalizeFilter(campaignId);
		Set<String> accountIdsFilter = DashboardQueries.normalizeAccountIds(accountIds);
		// 함의 인자는 "브랜드를 콕 집어 물었는가" — 이 표면엔 단수 파라미터가 없어 accountIds뿐이다.
		String accountTypeFilter = DashboardQueries.normalizeAccountType(accountType, accountIdsFilter != null);
		String sortKey = normalizeSort(sort, INFLUENCER_SORT_KEYS, SORT_VIEWS);
		boolean ascending = normalizeOrder(order);
		LocalDate from = DashboardQueries.parseDate(uploadedFrom, "uploadedFrom");
		LocalDate to = DashboardQueries.parseDate(uploadedTo, "uploadedTo");
		PageParams page = DashboardQueries.normalizePage(limit, offset);

		PerformanceContentAssembler.DashboardIndex index = assembler.index(principal.getUserId());
		Set<String> competitorIds = index.competitorBrandAccountIds();
		List<DashboardRef> filtered = index.refs().stream()
				.filter(r -> (sponsorshipFilter == null || sponsorshipFilter.equals(r.sponsorship()))
						&& DashboardQueries.matchesCampaign(r.campaignId(), campaignFilter)
						// 단수 필터 자리는 항상 null이다(이 표면엔 파라미터가 없다 — 위 javadoc).
						&& DashboardQueries.matchesBrand(r.brandAccountId(), null, accountIdsFilter)
						&& DashboardQueries.matchesAccountType(r.brandAccountId(), accountTypeFilter, competitorIds)
						&& DashboardQueries.withinUploadWindow(r.uploadedOn(), from, to))
				.toList();

		List<PerformanceInfluencerResponse> rows = PerformanceInfluencerAggregator.aggregate(filtered).stream()
				.sorted(influencerComparator(sortKey, ascending))
				.toList();
		List<PerformanceInfluencerResponse> data = page == null ? rows
				: rows.stream().skip(page.offset()).limit(page.limit()).toList();

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", rows.size());
		meta.put("page", DashboardQueries.pageMeta(page));
		return ApiResponse.ok(data, meta);
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

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", total);
		// 목록 상한 철폐(08-10) — 잘림이 없다. limit 키는 응답 형태 호환용으로 남기되 필터 후 전체
		// 건수와 같게 둔다(FE가 total > limit로 잘림을 판정해도 오탐이 없다). 페이지 크기는 meta.page다.
		meta.put("limit", total);
		meta.put("lastCollectedAt", KstTimestamps.toKstIso(lastCollectedAt));
		meta.put("statusCounts", statusCounts);
		meta.put("page", DashboardQueries.pageMeta(page));
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

	/**
	 * 인플루언서 정렬 비교자(§4) — 키가 <b>집계 행</b>이라 목록 {@link #comparator}와 따로 둔다. null
	 * 키는 order와 무관하게 항상 마지막이고(순위 밖), 타이브레이크는 {@code handle}이라 전순서다 —
	 * handle은 집계 행의 그룹 키(중복 없음)라 페이지 간 중복·누락이 생기지 않는다.
	 *
	 * <p>{@code latest}만 문자열 키다 — {@code latestPostAt}은 ISO date(YYYY-MM-DD)라 사전순이 곧
	 * 시간순이고, 값이 이미 문자열이라 되파싱할 이유가 없다.
	 */
	private static Comparator<PerformanceInfluencerResponse> influencerComparator(String sortKey,
			boolean ascending) {
		Comparator<PerformanceInfluencerResponse> byKey;
		if (SORT_LATEST.equals(sortKey)) {
			byKey = Comparator.comparing(PerformanceInfluencerResponse::latestPostAt,
					V1PerformanceDashboardController.<String>directional(ascending));
		} else {
			Function<PerformanceInfluencerResponse, Double> key = switch (sortKey) {
				case SORT_LIKES -> r -> toDouble(r.likes());
				case SORT_COMMENTS -> r -> toDouble(r.comments());
				// postCount는 int라 항상 값이 있다(0건 작성자는 행 자체가 없다).
				case SORT_POSTS -> r -> (double) r.postCount();
				case SORT_ENGAGEMENT -> V1PerformanceDashboardController::engagementRateOf;
				default -> r -> toDouble(r.views());   // SORT_VIEWS(기본값)
			};
			byKey = Comparator.comparing(key, V1PerformanceDashboardController.<Double>directional(ascending));
		}
		return byKey.thenComparing(PerformanceInfluencerResponse::handle);
	}

	/**
	 * 집계 행의 참여율 = {@code ratedEngaged ÷ ratedFollowers} — 분모가 미상이거나 0이면 순위 밖
	 * (null)이다. 분모·분자는 집계기가 같은 조건에서 함께 채우지만(둘 다 null이거나 둘 다 값),
	 * 나눗셈 앞이라 분자도 같이 본다.
	 *
	 * <p>비율을 응답 필드로 내리지 않고 여기서만 계산하는 이유는 응답 계약이다 — 집계 행은 분자·분모를
	 * 따로 내려 FE가 재평균 없이 합칠 수 있게 한다({@link PerformanceInfluencerResponse} javadoc).
	 */
	private static Double engagementRateOf(PerformanceInfluencerResponse row) {
		if (row.ratedFollowers() == null || row.ratedFollowers() <= 0 || row.ratedEngaged() == null) {
			return null;
		}
		return row.ratedEngaged() / (double) row.ratedFollowers();
	}

	/** 합계 정렬 키 — 미상(null)은 0으로 접지 않는다(0은 "전부 관측됐는데 0"이라 다른 값이다). */
	private static Double toDouble(Long value) {
		return value == null ? null : value.doubleValue();
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

	// ---------- 표면별 정규화(공용 정규화·술어는 DashboardQueries) ----------

	/** 목록 정렬 키 — 미지정·빈 값은 기본값 {@code uploaded}(종전 응답 순서), 값 공간 밖은 400. */
	private static String normalizeSort(String raw) {
		return normalizeSort(raw, SORT_KEYS, SORT_UPLOADED);
	}

	/**
	 * 정렬 키 정규화 — 값 공간·기본값은 표면마다 다르고(목록 vs 인플루언서) 400 메시지만 같다. 그래서
	 * {@link DashboardQueries}가 아니라 여기 둔다(공용화 기준은 "값 공간이 표면 간에 같아야 하는가").
	 */
	private static String normalizeSort(String raw, List<String> allowed, String fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		if (!allowed.contains(raw)) {
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
}
