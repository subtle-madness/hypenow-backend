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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인플루언서 집계 표면(스펙 §6-6, 2026-08-27 설계 D) — 브랜드를 태그한 게시물을 <b>작성자 단위</b>로
 * 접어 내려준다. 인증 필수, monitoring 비활성 환경에선 표면 자체가 없다(게시물 컨트롤러와 같은 게이트).
 *
 * <p>이 API가 신설된 이유는 FE가 게시물 목록 전량을 받아 브라우저에서 접고 있었기 때문이다 —
 * 페이지네이션(2026-08-27)이 들어오는 순간 그 전량 전제가 깨져, 인플루언서 화면의 수가 "현재 페이지
 * 안에 있는 것"으로 잘린다. 접는 규칙 자체는 FE {@code brand-influencers.ts}를 1:1 이식한
 * {@link BrandInfluencerAggregator}가 정본이고(같은 화면에서 두 벌의 수가 생기지 않게), 이 컨트롤러는
 * 모수 구성(소유권·계정별 창·기간)과 파라미터 계약만 책임진다.
 *
 * <p>모수는 게시물 목록과 <b>같은</b> 인덱스 기계를 탄다({@link BrandPostAssembler#indexForBrand}) —
 * 경량 ref에 게시자·협찬·업로드일이 이미 실려 있어 카드 하이드레이트가 전혀 필요 없다. 지표는
 * 게시물별 최신 스냅샷 1행 프로젝션({@link BrandReadRepository#findLatestMetricsForBrand})만 읽는다
 * (시계열 전량은 게시물당 최대 365행이라 집계 경로에 싣지 않는다).
 *
 * <p>다계정은 요청 {@code accountIds} 순서대로 처리하고, 같은 게시물이 두 계정을 태그했으면
 * shortcode 기준 1회만 집계한다(먼저 온 계정 소속) — 두 번 세면 그 게시물의 성과가 두 배로 접힌다.
 * 계정별 표시 창({@code brand_monitorings.collection_months})은 각 링크의 값으로 따로 적용한다:
 * 같은 브랜드라도 유저가 신청한 기간이 다르고, 창은 관계 속성이다.
 */
@RestController
@RequestMapping("/v1/brand-monitoring")
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class V1BrandInfluencersController {

	private static final Logger log = LoggerFactory.getLogger(V1BrandInfluencersController.class);

	/** 페이지 크기 상한·기본값 — 게시물 목록(6.1 리더보드 복제)과 같은 캡을 쓴다. */
	private static final int PAGE_LIMIT_MAX = 100;
	private static final int PAGE_LIMIT_DEFAULT = 100;

	private static final String FILTER_ALL = "all";
	private static final String SPONSORSHIP_SPONSORED = "sponsored";
	private static final String SPONSORSHIP_ORGANIC = "organic";

	private final BrandLinkRepository linkRepository;
	private final BrandReadRepository brandReadRepository;
	private final BrandPostAssembler assembler;
	private final Clock clock;

	public V1BrandInfluencersController(BrandLinkRepository linkRepository,
			BrandReadRepository brandReadRepository, BrandPostAssembler assembler, Clock clock) {
		this.linkRepository = linkRepository;
		this.brandReadRepository = brandReadRepository;
		this.assembler = assembler;
		this.clock = clock;
	}

	/**
	 * 작성자 단위 집계 목록 — {@code meta {total, offset, limit}}는 flat이다(게시물 목록의 중첩
	 * {@code meta.page}와 다른 이유: 이 표면엔 "수집 상한" 의미의 {@code limit}이 없어 이름 충돌이
	 * 없다). {@code total}은 <b>필터 적용 후</b> 개수고, {@code limit}은 전량 응답이면 null이다
	 * (키는 유지 — 계약 무결성 규칙 #1).
	 */
	@GetMapping("/influencers")
	public ApiResponse<List<BrandInfluencerResponse>> list(
			@AuthenticationPrincipal AppUserDetails principal,
			@RequestParam String accountIds,
			@RequestParam(required = false) String uploadedFrom,
			@RequestParam(required = false) String uploadedTo,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String follower,
			@RequestParam(required = false) String sponsorship,
			@RequestParam(required = false) Integer offset,
			@RequestParam(required = false) Integer limit) {
		long userId = principal.getUserId();
		List<Long> brandIds = parseAccountIds(accountIds);
		String sortKey = normalizeSort(sort);
		String sponsorshipFilter = normalizeSponsorship(sponsorship);
		V1BrandPostsController.FollowerBand band = V1BrandPostsController.parseFollower(follower);
		String keywordLower = normalizeKeyword(keyword);
		LocalDate from = parseDate(uploadedFrom, "uploadedFrom");
		LocalDate to = parseDate(uploadedTo, "uploadedTo");
		PageParams page = normalizePage(limit, offset);

		// 소유권 — 요청 id가 전부 내 활성 링크 안에 있어야 한다(브랜드/경쟁사 구분 없음). 하나라도
		// 아니면 존재 여부를 흘리지 않고 게시물 표면과 같은 FORBIDDEN 관용구로 끊는다.
		Map<Long, BrandLinkRow> linksByBrandId = linkRepository.findAllActiveByUser(userId).stream()
				.collect(Collectors.toMap(BrandLinkRow::brandId, Function.identity(), (a, b) -> a,
						LinkedHashMap::new));
		for (Long brandId : brandIds) {
			if (!linksByBrandId.containsKey(brandId)) {
				throw V1ApiException.forbidden("FORBIDDEN", "브랜드 계정을 찾을 수 없거나 접근 권한이 없어요.");
			}
		}

		// 시계는 요청당 한 번만 읽는다 — 계정마다 다시 읽으면 자정을 걸친 응답에서 계정별로 컷이 다르다.
		LocalDate today = LocalDate.ofInstant(clock.instant(), KstTimestamps.KST);
		List<BrandInfluencerAggregator.InfluencerPost> posts = new ArrayList<>();
		for (Long brandId : brandIds) {
			BrandLinkRow link = linksByBrandId.get(brandId);
			Optional<BrandAccountRow> account = brandReadRepository.findAccount(brandId);
			if (account.isEmpty()) {
				// 연결은 살아 있는데 monitoring 쪽 계정 행이 없는 상태 — 집계를 죽이지 않고 그 계정만 뺀다
				// (PerformanceContentAssembler.loadBrandPool 관용구).
				log.warn("브랜드 연결의 monitoring 계정 행 부재 — 인플루언서 집계 생략 brandId={}", brandId);
				continue;
			}
			posts.addAll(accountPosts(userId, account.get(), link, today, from, to));
		}

		List<BrandInfluencerResponse> rows = BrandInfluencerAggregator.summarize(
				BrandInfluencerAggregator.dedupeByShortcode(posts)).stream()
				.filter(r -> BrandInfluencerAggregator.matchesKeyword(r, keywordLower))
				.filter(r -> BrandInfluencerAggregator.matchesFollower(r, band))
				.filter(r -> BrandInfluencerAggregator.matchesSponsorship(r, sponsorshipFilter))
				.toList();
		List<BrandInfluencerResponse> sorted = BrandInfluencerAggregator.sort(rows, sortKey);
		List<BrandInfluencerResponse> body = page == null ? sorted
				: sorted.stream().skip(page.offset()).limit(page.limit()).toList();

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", sorted.size());
		meta.put("offset", page == null ? 0 : page.offset());
		meta.put("limit", page == null ? null : page.limit());
		return ApiResponse.ok(body, meta);
	}

	/**
	 * 계정 1개의 집계 입력 — 창(링크 표시 창 + 업로드 기간)을 통과한 ref만 최신 지표와 짝지어
	 * {@link BrandInfluencerAggregator.InfluencerPost}로 접는다. 인덱스는 정렬 키가 필요 없어
	 * {@code withViews=false}로 부른다(집계 조회수는 최신 지표 프로젝션에서 온다 — 같은 산지를
	 * 두 번 읽지 않는다).
	 */
	private List<BrandInfluencerAggregator.InfluencerPost> accountPosts(long userId, BrandAccountRow account,
			BrandLinkRow link, LocalDate today, LocalDate from, LocalDate to) {
		LocalDate windowStart = BrandPostWindows.linkWindowStart(today, link.collectionMonths());
		BrandPostAssembler.BrandPostIndex index = assembler.indexForBrand(userId, account, false);
		List<BrandPostAssembler.PostRef> refs = index.refs().stream()
				.filter(r -> BrandPostWindows.withinLinkWindow(r, windowStart))
				.filter(r -> BrandPostWindows.withinUploadWindow(r.uploadedOn(), from, to))
				.toList();
		if (refs.isEmpty()) {
			return List.of();
		}
		Map<String, BrandReadRepository.LatestMetricsRow> metricsByCode =
				brandReadRepository.findLatestMetricsForBrand(account.id(), BrandPostAssembler.windowCutoff(), true)
						.stream()
						.collect(Collectors.toMap(BrandReadRepository.LatestMetricsRow::shortCode,
								Function.identity(), (a, b) -> a, LinkedHashMap::new));

		List<BrandInfluencerAggregator.InfluencerPost> out = new ArrayList<>(refs.size());
		for (BrandPostAssembler.PostRef ref : refs) {
			// 작성자 미상은 사람 단위로 접을 키가 없어 제외한다(FE 어댑터도 authorUsername을 ""로 접어
			// Map을 만들므로 빈 문자열도 같은 취급이다 — 판정은 집계기가 한다).
			if (ref.authorUsername() == null || ref.authorUsername().isBlank()) {
				continue;
			}
			out.add(toInfluencerPost(ref, metricsByCode.get(ref.shortcode()),
					index.legacyByCode().get(ref.shortcode())));
		}
		return out;
	}

	/**
	 * ref + 최신 지표 → 집계 입력 1행. 피드 views null 폴드는 스냅샷 행의 {@code content_type} 기준이다
	 * ({@code BrandPostAssembler.snapshotOf} 서빙 규칙 동형 — 피드 조회수는 구조적으로 없다).
	 *
	 * <p>최신 지표 행이 없으면 과도기 폴백(레거시 direct) 카드의 최신 스냅샷을 본다 — 그 카드는
	 * 브랜드 스냅샷 테이블이 아니라 레거시 조립에서 오므로 {@code findLatestMetricsForBrand}에
	 * 잡히지 않는다. 둘 다 없으면 지표 미상이고, 그 게시물은 {@code postCount}에만 기여한다.
	 */
	static BrandInfluencerAggregator.InfluencerPost toInfluencerPost(BrandPostAssembler.PostRef ref,
			BrandReadRepository.LatestMetricsRow m, BrandPostResponse legacyCard) {
		Long views;
		Long likes;
		boolean likesHidden;
		Long comments;
		if (m != null) {
			boolean isReels = "REELS".equalsIgnoreCase(m.contentType());
			views = isReels ? m.views() : null;
			likes = m.likes();
			likesHidden = m.likesHidden();
			comments = m.comments();
		} else if (legacyCard != null && legacyCard.latestSnapshot() != null) {
			var s = legacyCard.latestSnapshot();
			views = s.views();
			likes = s.likes();
			likesHidden = Boolean.TRUE.equals(s.likesHidden());
			comments = s.comments();
		} else {
			views = null;
			likes = null;
			likesHidden = false;
			comments = null;
		}
		return new BrandInfluencerAggregator.InfluencerPost(ref.shortcode(), ref.authorUsername(),
				ref.authorFullName(), ref.authorProfilePicUrl(), ref.authorFollowers(),
				ref.takenAtKst(), BrandSponsorshipClassifier.SPONSORED.equals(ref.sponsorship()),
				views, likes, likesHidden, comments);
	}

	// ---------- 파라미터 ----------

	/**
	 * {@code accountIds} 파싱 — 필수·쉼표 구분이고 빈 값·비숫자 토큰은 400이다(path 파라미터가 아니라
	 * 쿼리라 게시물 상세의 404 관용구를 쓰지 않는다: 여기서 숫자가 아닌 값은 "없는 계정"이 아니라
	 * 잘못된 요청이다). 중복 id는 접어 계정을 두 번 훑지 않게 하되 요청 순서는 유지한다 —
	 * 교차 중복 제거가 "먼저 온 계정이 이긴다" 규칙이라 순서가 결과를 바꾼다.
	 */
	private static List<Long> parseAccountIds(String raw) {
		if (raw == null || raw.isBlank()) {
			throw V1ApiException.validation("accountIds는 필수예요.");
		}
		Set<Long> ids = new LinkedHashSet<>();
		for (String token : raw.split(",", -1)) {
			String trimmed = token.trim();
			if (trimmed.isEmpty()) {
				throw V1ApiException.validation("accountIds 값이 올바르지 않아요.");
			}
			try {
				ids.add(Long.parseLong(trimmed));
			} catch (NumberFormatException e) {
				throw V1ApiException.validation("accountIds 값이 올바르지 않아요.");
			}
		}
		return List.copyOf(ids);
	}

	/**
	 * 정렬 토큰 7종({@link BrandInfluencerAggregator#SORT_KEYS}) — 미지정·빈 값은 기본 {@code posts}.
	 * 집계기는 모르는 토큰을 posts로 접지만(FE {@code default} 이식), 표면 계약에서는 400으로 끊는다:
	 * 오타가 조용히 다른 정렬로 응답되면 FE가 잘못된 화면을 정상으로 믿는다.
	 */
	private static String normalizeSort(String raw) {
		if (raw == null || raw.isBlank()) {
			return BrandInfluencerAggregator.SORT_POSTS;
		}
		if (!BrandInfluencerAggregator.SORT_KEYS.contains(raw)) {
			throw V1ApiException.validation("sort 값이 올바르지 않아요.");
		}
		return raw;
	}

	/** 미지정·{@code all}은 필터 없음(null), {@code sponsored}/{@code organic} 외 값은 400. */
	private static String normalizeSponsorship(String raw) {
		if (raw == null || raw.isBlank() || FILTER_ALL.equals(raw)) {
			return null;
		}
		if (SPONSORSHIP_SPONSORED.equals(raw) || SPONSORSHIP_ORGANIC.equals(raw)) {
			return raw;
		}
		throw V1ApiException.validation("sponsorship 값이 올바르지 않아요.");
	}

	/** trim 후 빈 문자열은 필터 없음(null) — 집계기가 멱등하게 다시 접지만 산지에서 한 번 접어 둔다. */
	private static String normalizeKeyword(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		return raw.trim().toLowerCase(Locale.ROOT);
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

	/**
	 * 페이지 파라미터 정규화 — 게시물 목록 {@code normalizePage}와 같은 규칙이다: 둘 다 생략이면
	 * null(전량), 하나라도 있으면 페이지 모드(limit 기본 100·1..100, offset 기본 0·≥0), 범위 밖 400.
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
}
