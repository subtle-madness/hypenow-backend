package com.celfit.was.v1.perfdashboard;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.v1.brandmonitoring.BrandAccountType;
import com.celfit.was.v1.brandmonitoring.BrandPostAssembler;
import com.celfit.was.v1.brandmonitoring.BrandPostResponse;
import com.celfit.was.v1.brandmonitoring.BrandSponsorshipClassifier;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import com.celfit.was.v1.perfdashboard.PerformanceContentResponse.PerformanceItemResponse;
import com.celfit.was.v1.perfdashboard.PerformanceContentResponse.PerformancePostResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 성과 대시보드 통합 조립(스펙 §7-1, 2026-08-18 direct 통합 §결정 3로 2계열 재편) — 콘텐츠를
 * shortcode 하나로 합친다.
 *
 * <ul>
 *   <li><b>individual</b>: 레거시 추적 아이템 중 브랜드 풀에 없는 것(개인 캠페인 등록분 + 이관 잡이
 *       아직 못 옮긴 direct 등록분 — 후자는 이관되면 자연히 브랜드 풀 겹침으로 옮겨간다).</li>
 *   <li><b>브랜드 풀(tagged ∪ direct)</b>: {@link BrandPostAssembler}가 한 산지({@code brand_tagged_post})
 *       에서 조립한다. {@code source}는 그 응답의 {@link BrandPostResponse#source()}를 그대로 쓴다
 *       (direct/tagged 구분은 산지 컬럼의 파생값일 뿐 조립 경로가 다르지 않다).</li>
 * </ul>
 *
 * <p>3계열(individual·direct·tagged)이 2계열로 준 것이 통합의 핵심이다 — direct·tagged가 이제
 * 같은 행이라 예전처럼 "direct 아이템이 본체, tagged는 부가 관측"으로 나눌 필요가 없다. 대표 산지는
 * 여전히 하나다: 레거시 아이템이 있으면(individual) 그 아이템이 본체이고, 겹치는 브랜드 풀 관측은
 * {@code additionalSources}로 남기면서 <b>스냅샷만</b> 지표별로 병합한다. 레거시 없이 브랜드 풀에만
 * 있는 게시물은 레거시 행이 없으므로 아이템을 합성한다({@code "bt_"+shortcode}).
 *
 * <p>필터·정렬·statusCounts는 여기서 하지 않는다(컨트롤러 몫) — 이 클래스는 필터 전 "전량"을
 * 업로드 최신순으로 돌려준다.
 *
 * <p>monitoring 서브시스템이 꺼진 환경에선 브랜드 계열 빈이 아예 없다 — 그래서 브랜드 의존은
 * {@link Optional}이고, 그 경우 브랜드 풀 계열을 건너뛴다(대시보드 표면은 살아 있어야 한다).
 */
@Component
public class PerformanceContentAssembler {

	/** 대표 산지 값 공간 — 컨트롤러 필터(Task 10)가 리터럴을 다시 적지 않게 상수로 노출한다. */
	public static final String SOURCE_INDIVIDUAL = "individual";
	public static final String SOURCE_DIRECT = "direct";
	public static final String SOURCE_TAGGED = "tagged";

	/** 브랜드 풀 전용 합성 아이템의 id 접두 — 레거시 숫자 id와 절대 충돌하지 않게 한다(설계 결정 7).
	 * 2026-08-18 direct 통합 이후 tagged-only뿐 아니라 direct-only(레거시 아이템이 없는 direct 등록)
	 * 콘텐츠에도 이 접두가 붙는다 — "브랜드 풀 콘텐츠 전반"의 합성 id다(FE 통지 §6-4). */
	private static final String SYNTHETIC_ID_PREFIX = "bt_";
	/** 합성 아이템의 표시용 추적 기간(레거시 trackingDays 필드 셰이프를 채우기 위한 값일 뿐 실제 나이
	 * 티어 정책에 쓰이지 않는다) — 값은 08-09 이전부터 90으로 고정돼 있었고, 실제 표시 윈도우는
	 * 365일이다({@link BrandPostAssembler#WINDOW_DAYS}). 2026-08-18 direct 통합에서 주석 불일치를
	 * 발견했으나 값은 바꾸지 않는다(FE 통지 추가 항목을 만들지 않기 위해 — 표시용 필드일 뿐 서버
	 * 판정을 좌우하지 않는다). */
	private static final int TAGGED_TRACKING_DAYS = 90;
	private static final String MODE_URL = "url";

	/** 레거시 응답엔 shortcode 필드가 없어 post.url 경로 세그먼트에서 뽑는다(스펙 §7-1). */
	private static final Pattern SHORTCODE_PATTERN = Pattern.compile("/(?:p|reel|reels)/([A-Za-z0-9_-]+)");

	private static final Logger log = LoggerFactory.getLogger(PerformanceContentAssembler.class);

	private final TrackingItemAssembler trackingItemAssembler;
	private final BrandLinkRepository linkRepository;
	private final CampaignRepository campaignRepository;
	private final Optional<BrandReadRepository> brandReadRepository;
	private final Optional<BrandPostAssembler> brandPostAssembler;

	public PerformanceContentAssembler(TrackingItemAssembler trackingItemAssembler,
			BrandLinkRepository linkRepository, CampaignRepository campaignRepository,
			Optional<BrandReadRepository> brandReadRepository, Optional<BrandPostAssembler> brandPostAssembler) {
		this.trackingItemAssembler = trackingItemAssembler;
		this.linkRepository = linkRepository;
		this.campaignRepository = campaignRepository;
		this.brandReadRepository = brandReadRepository;
		this.brandPostAssembler = brandPostAssembler;
	}

	/** 필터 전 전량(업로드 최신순, 업로드일 미상은 마지막) — 댓글 포함. 단건 조회(§7-1)가 소비한다. */
	public Assembled assemble(long userId) {
		return assemble(userId, true);
	}

	/**
	 * 댓글 없는 전량 조립(08-12 고정 지연 대응) — 목록·비교 표면용. 두 표면은 댓글을 렌더하지 않는데
	 * 운영 실측(08-12 덤프 하니스)에서 조립 시간의 절반 이상이 댓글 배치 조회 + 수만 행 매핑이었다.
	 * 결과 콘텐츠의 {@code recentComments}는 빈 목록, {@code commentsCollectedCount}는 0이다 —
	 * 스냅샷 유래 지표({@code commentsTotal}·{@code commentsHidden})와 나머지 필드는 전부 동일하다.
	 * 댓글이 필요한 단건 조회는 {@link #assemble(long)}을 그대로 쓴다.
	 */
	public Assembled assembleSlim(long userId) {
		return assemble(userId, false);
	}

	private Assembled assemble(long userId, boolean withComments) {
		TrackingItemAssembler.AssembledList legacy = trackingItemAssembler.assembleList(userId);
		// 활성 링크는 monitoring 게이트 <b>밖</b>에서 한 번 읽는다(08-12) — 구독 타입 판정에 필요하고,
		// BrandLinkRepository는 app DataSource라 monitoring 비활성 환경에서도 살아 있다.
		List<BrandLinkRow> links = linkRepository.findAllActiveByUser(userId);
		Set<String> competitorIds = competitorBrandAccountIds(links);
		BrandPool brandPool = loadBrandPool(userId, links, withComments);
		Map<Long, CampaignRow> campaignsById = campaignRepository.findByUser(userId).stream()
				.collect(Collectors.toMap(CampaignRow::id, Function.identity()));

		List<PerformanceContentResponse> contents = new ArrayList<>();
		Set<String> consumedCodes = new LinkedHashSet<>();
		for (TrackingItemResponse item : legacy.items()) {
			String shortcode = shortcodeOf(item.post() == null ? null : item.post().url());
			BrandPostResponse overlap = shortcode == null ? null : brandPool.byShortcode().get(shortcode);
			if (overlap != null) {
				consumedCodes.add(shortcode);
			}
			contents.add(fromLegacy(item, shortcode, overlap, withComments));
		}
		for (Map.Entry<String, BrandPostResponse> entry : brandPool.byShortcode().entrySet()) {
			if (!consumedCodes.contains(entry.getKey())) {
				contents.add(fromBrandPost(entry.getValue(), campaignsById));
			}
		}

		contents.sort(Comparator
				.comparing(PerformanceContentAssembler::uploadedOn, Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(c -> c.item().id()));
		return new Assembled(List.copyOf(contents),
				lastCollectedAt(legacy.lastCollectedAt(), brandPool.lastSweptAt()), competitorIds);
	}

	// ---------- 레거시 계열 ----------

	/**
	 * 레거시 아이템 1건 → 대시보드 콘텐츠. 아이템 본체(상태·기간·캠페인·핸들)는 전부 레거시가 정본이고,
	 * 겹치는 브랜드 풀 관측이 있으면 스냅샷 병합·협찬 승격·additionalSources·귀속만 얹는다(스펙 §7-1).
	 *
	 * <p>2026-08-18 direct 통합 이후 direct 판정은 더 이상 별도 매핑 조회가 아니다 — 겹치는
	 * {@code overlap}의 {@link BrandPostResponse#source()}를 그대로 쓴다(tagged/direct 구분은 그
	 * 응답의 파생값). 레거시 아이템이 브랜드 풀 어디에도 없으면 individual이다. <b>과도기 한정</b>:
	 * 이관 잡(M2)이 아직 못 옮긴 direct 등록분은 브랜드 풀에 없어(그 매핑은 {@code
	 * BrandPostAssembler.assembleLegacyPending} 폴백에서만 조립된다) 이 표면에서 individual로 보인다
	 * — 이관되면 자연히 direct로 옮겨간다(설계 §결정 3).
	 */
	private static PerformanceContentResponse fromLegacy(TrackingItemResponse item, String shortcode,
			BrandPostResponse overlap, boolean withComments) {
		PerformancePostResponse post = legacyPost(item, shortcode, overlap, withComments);
		return new PerformanceContentResponse(
				new PerformanceItemResponse(item.id(), item.mode(), item.status(), item.handle(),
						item.displayName(), item.profileImageUrl(), item.followers(), item.lastUploadedAt(),
						item.campaignId(), item.campaignName(), item.sourceUrl(), item.registeredAt(),
						item.trackingDays(), item.keywords(), post, item.nextCheckAt()),
				overlap == null ? SOURCE_INDIVIDUAL : overlap.source(),
				sponsorshipOf(item, overlap),
				// 게시물이 아직 없는 아이템(collecting·detecting·not_uploaded)은 shortcode 자체가 없다 —
				// 그 콘텐츠는 item.id로만 식별된다(스펙 §7-1).
				shortcode,
				overlap == null ? List.of() : List.of(overlap.source()),
				attributedBrandAccountId(overlap));
	}

	/**
	 * 브랜드 귀속 결정(2026-08-18 direct 통합 후 단순화) — direct와 tagged가 이제 한 행이므로 귀속이
	 * 곧 그 행의 {@code brand_id}다. 겹치는 브랜드 풀 관측이 없으면(individual) 귀속도 없다.
	 * {@link #ownFirst}가 정하는 "여러 브랜드에 걸친 겹침일 때 own이 이긴다" 규칙은 브랜드 풀 조립
	 * 단계({@link #loadBrandPool})에서 이미 반영돼 있다 — 여기서 다시 판단할 대상이 없다.
	 */
	private static String attributedBrandAccountId(BrandPostResponse overlap) {
		return overlap == null ? null : overlap.brandAccountId();
	}

	private static PerformancePostResponse legacyPost(TrackingItemResponse item, String shortcode,
			BrandPostResponse overlap, boolean withComments) {
		TrackingItemResponse.TrackedPostResponse post = item.post();
		if (post == null) {
			return null;
		}
		List<TrackingItemResponse.SnapshotResponse> snapshots =
				overlap == null ? post.snapshots() : mergeSnapshots(post.snapshots(), overlap.snapshots());
		// 댓글은 병합하지 않는다(두 산지의 id 공간·정렬이 달라 섞으면 순서가 무의미해진다) — 레거시가
		// 한 건도 못 모은 경우에만 브랜드 수집분으로 메운다(빈 목록을 그대로 내보내는 것보다 낫다).
		// 슬림 조립(목록·비교)은 댓글을 아예 싣지 않는다 — 두 표면은 댓글을 렌더하지 않는다(08-12).
		List<TrackingItemResponse.PostCommentResponse> comments = !withComments ? List.of()
				: !post.recentComments().isEmpty() || overlap == null ? post.recentComments() : overlap.recentComments();

		return new PerformancePostResponse(post.url(), shortcode, post.contentType(), post.uploadedAt(),
				post.caption(), post.matchedKeywords(), post.thumbnailUrl(), post.hiddenAt(), snapshots,
				commentsTotal(snapshots), commentsHidden(snapshots), comments.size(), comments);
	}

	/**
	 * 협찬 판정 — 레거시엔 {@code is_paid_partnership} 관측이 없어 캡션 키워드만으로 판정하고,
	 * 겹치는 tagged가 있으면 그 관측으로 승격한다(브랜드 스윕만 볼 수 있는 신호라 버리면 정보 손실).
	 * 캡션은 레거시 우선이되 비어 있으면(메타 미수집 시 레거시는 빈 문자열) tagged 캡션으로 폴백한다.
	 */
	private static String sponsorshipOf(TrackingItemResponse item, BrandPostResponse overlap) {
		String legacyCaption = item.post() == null ? null : item.post().caption();
		String caption = legacyCaption != null && !legacyCaption.isBlank() ? legacyCaption
				: (overlap == null ? null : overlap.caption());
		return BrandSponsorshipClassifier.classify(overlap == null ? null : overlap.isPaidPartnership(), caption);
	}

	// ---------- 브랜드 풀 계열 ----------

	/**
	 * 브랜드 풀 전용(tagged-only·direct-only 공통) 콘텐츠 — 레거시 행이 없어 아이템을 합성한다
	 * (설계 결정 7). id 접두 {@code bt_}는 레거시 숫자 id와의 충돌을 막고, {@code canonicalPostId}는
	 * 순수 shortcode 그대로다. {@code source}는 {@link BrandPostResponse#source()}를 그대로 쓴다
	 * (2026-08-18 direct 통합 이후 tagged든 direct든 조립 경로가 하나다).
	 *
	 * <p>등록일은 브랜드가 이 게시물을 처음 본 날(first_seen), 추적 기간은 표시용 상수(90) —
	 * {@link #TAGGED_TRACKING_DAYS} 참고. 상태는 {@link BrandPostResponse#trackingStatus()}를 그대로
	 * 승계한다 — 삭제·비공개 감지(hidden, 2026-08-25 설계)가 대시보드에도 반영된다.
	 *
	 * <p>{@code campaignId}·{@code campaignName}은 {@code post.campaignIds()}의 head로 채운다
	 * (비면 둘 다 null). 여러 캠페인이 붙어 있어도 첫 번째만 쓴다 — 응답 필드가 단수라서다(다중 부착
	 * UI는 후속, 설계 §결정 3).
	 */
	private static PerformanceContentResponse fromBrandPost(BrandPostResponse post,
			Map<Long, CampaignRow> campaignsById) {
		String handle = post.authorUsername() == null ? "" : post.authorUsername().toLowerCase(Locale.ROOT);
		String displayName = post.authorFullName() == null || post.authorFullName().isBlank()
				? handle : post.authorFullName();

		PerformancePostResponse dashboardPost = new PerformancePostResponse(post.postUrl(), post.shortcode(),
				post.contentType(), post.takenAt(), post.caption(),
				// 브랜드 풀 게시물은 키워드 감지 경로가 아니다(브랜드 계정 태그·직접 등록이 곧 편입 사유).
				List.of(), post.thumbnailUrl(), null, post.snapshots(), post.commentsTotal(),
				post.commentsHidden(), post.commentsCollectedCount(), post.recentComments());
		String campaignId = post.campaignIds().isEmpty() ? null : post.campaignIds().get(0);
		String campaignName = campaignId == null ? null
				: Optional.ofNullable(campaignsById.get(Long.valueOf(campaignId))).map(CampaignRow::name)
						.orElse(null);

		return new PerformanceContentResponse(
				new PerformanceItemResponse(SYNTHETIC_ID_PREFIX + post.shortcode(), MODE_URL, post.trackingStatus(),
						handle, displayName, post.authorProfilePicUrl(), post.authorFollowers(),
						// 게시자의 마지막 업로드 시각은 브랜드 파이프라인이 관측하지 않는다(프로필 스윕 대상이 아님).
						null, campaignId, campaignName, post.postUrl(), dateOf(post.trackingStartedAt()),
						TAGGED_TRACKING_DAYS, null, dashboardPost, null),
				post.source(), post.sponsorship(), post.shortcode(), List.of(), post.brandAccountId());
	}

	/**
	 * 경쟁사 구독의 brandId 집합(08-12) — 링크 행만으로 확정된다. monitoring 계정 행 유무·서브시스템
	 * 활성 여부와 무관하다: 그 브랜드의 direct 콘텐츠는 계정 행이 없어도 목록에 실리기 때문이다.
	 */
	private static Set<String> competitorBrandAccountIds(List<BrandLinkRow> links) {
		Set<String> competitorIds = new LinkedHashSet<>();
		for (BrandLinkRow link : links) {
			if (isCompetitor(link)) {
				competitorIds.add(String.valueOf(link.brandId()));
			}
		}
		return Set.copyOf(competitorIds);
	}

	private static boolean isCompetitor(BrandLinkRow link) {
		return BrandAccountType.COMPETITOR.equals(link.accountType());
	}

	/**
	 * 같은 shortcode가 여러 브랜드에 태그된 경우의 귀속 순서(08-12) — <b>내 브랜드 귀속이 경쟁사
	 * 귀속을 이긴다</b>. own 링크를 먼저 순회시켜 {@code putIfAbsent}의 동률이 own 쪽으로 풀리게 한다.
	 *
	 * <p>이 규칙이 필요한 이유: 귀속(brandAccountId)은 이제 <b>표시</b>가 아니라 <b>범위</b>를 정한다.
	 * 내 게시물이 내 브랜드와 경쟁사 브랜드에 동시에 태그돼 있고 경쟁사 연결이 더 오래됐다는 이유만으로
	 * 그 콘텐츠가 기본 범위·statusCounts에서 빠지면, 연결 순서가 "내 성과가 보이는지"를 정하게 된다.
	 *
	 * <p>타입 <b>안</b>에서는 기존 규칙(먼저 연결한 브랜드가 이긴다, 08-07 개정) 그대로다 — 원본 순서를
	 * 유지한 채 own 묶음과 competitor 묶음의 순서만 바꾼다.
	 */
	private static List<BrandLinkRow> ownFirst(List<BrandLinkRow> links) {
		List<BrandLinkRow> ordered = new ArrayList<>(links.size());
		for (BrandLinkRow link : links) {
			if (!isCompetitor(link)) {
				ordered.add(link);
			}
		}
		for (BrandLinkRow link : links) {
			if (isCompetitor(link)) {
				ordered.add(link);
			}
		}
		return ordered;
	}

	/**
	 * 활성 브랜드 연결이 있을 때만 브랜드 풀 계열을 조립한다 — 없으면 monitoring DB를 아예 건드리지
	 * 않는다. 다계정(08-07 개정)은 연결 순서대로 병합하되 own 묶음이 먼저다({@link #ownFirst}) —
	 * 같은 shortcode가 여러 브랜드에 태그돼 있으면 내 브랜드가, 같은 타입 안에서는 먼저 연결한
	 * 브랜드가 이긴다(putIfAbsent). lastSweptAt은 브랜드들 중 가장 늦은 값이라 순회 순서와 무관하다.
	 *
	 * @param links 호출부가 이미 읽어 둔 활성 링크 — 여기서 다시 조회하지 않는다(monitoring이 켜져 있든
	 *              꺼져 있든 링크 조회는 요청당 한 번이다).
	 * @param userId 시딩(캠페인 연결) 판정 스코프(2026-08-18 캠페인 도출 개정) — {@link
	 *               BrandPostAssembler#assembleBrandPosts} 호출에 그대로 넘긴다.
	 */
	private BrandPool loadBrandPool(long userId, List<BrandLinkRow> links, boolean withComments) {
		if (brandReadRepository.isEmpty() || brandPostAssembler.isEmpty() || links.isEmpty()) {
			return BrandPool.EMPTY;   // monitoring 비활성이거나 연결 0건 — 레거시 계열만
		}

		Map<String, BrandPostResponse> byShortcode = new LinkedHashMap<>();
		OffsetDateTime lastSweptAt = null;
		for (BrandLinkRow link : ownFirst(links)) {
			Optional<BrandAccountRow> account = brandReadRepository.get().findAccount(link.brandId());
			if (account.isEmpty()) {
				// 연결은 살아 있는데 monitoring 쪽 계정 행이 없는 상태 — 대시보드를 죽이지 않고 그 브랜드만 뺀다.
				log.warn("브랜드 연결의 monitoring 계정 행 부재 — 브랜드 풀 생략 brandId={}", link.brandId());
				continue;
			}
			// 지표 집계라 정산 전 게시물도 담는다(ALL) — 미정산분도 스냅샷(지표)은 이미 있다(열거에서
			// 오고 monitoring processPage가 저장한다). 없는 건 댓글·게시자뿐이라 빼면 지표가 과소 계상된다.
			// 성과 대시보드는 실수집 범위만 집계한다(커버리지 클램프 on, 수집 상한 v2 §7-1) — 컷 밖
			// 레거시 수집분이 요약·버킷에 섞이면 covered=false(빗금) 구간에 값이 실려 화면이 모순된다.
			for (BrandPostResponse post : brandPostAssembler.get()
					.assembleBrandPosts(userId, account.get(), withComments, BrandPostAssembler.BrandPostScope.ALL,
							true, link.accountType())) {
				byShortcode.putIfAbsent(post.shortcode(), post);
			}
			lastSweptAt = lastCollectedAt(lastSweptAt, account.get().lastSweptAt());
		}
		return new BrandPool(byShortcode, lastSweptAt);
	}

	// ---------- 스냅샷 병합 ----------

	/**
	 * 스냅샷 병합(설계 결정 6) — 날짜별로 합치고, 지표별로 non-null을 우선하되 둘 다 값이면
	 * <b>브랜드 값</b>을 쓴다(브랜드 스윕 03:00이 레거시 02:00보다 늦어 "늦게 수집된 원천값" 규칙에 맞다).
	 * 숨김 불리언은 "관측된 켜짐 우선" — 어느 쪽이든 true면 true다(한쪽이 못 본 것을 false로 덮지 않는다).
	 *
	 * <p>날짜 키는 앞 10자다 — 산지에 따라 날짜와 타임스탬프가 섞여 들어와도 같은 하루로 접힌다.
	 * 결과는 날짜 오름차순(계약).
	 *
	 * <p><b>기간 경계는 의도적으로 열어 둔다</b> — 레거시 추적 기간 <b>밖</b>(등록 전·종료 후)의 브랜드
	 * 스냅샷도 그대로 실린다. 레거시 단독 경로는 소급 금지 하한을 걸지만
	 * ({@code TrackingItemAssembler.snapshotFloor}) 여기서는 클램프하지 않는다:
	 * <ol>
	 *   <li>FE 병합 규칙은 "동일 날짜·지표별 최신 수집값"만 규정하고 범위를 제한하지 않는다.</li>
	 *   <li>FE 증가분 계산은 최신 스냅샷 2개만 쓰므로 앞쪽에 날짜가 더 붙어도 무해하다.</li>
	 *   <li>클램프는 실제로 관측된 값을 폐기하는 것이고, 같은 게시물의 브랜드 화면(§6-1은 90일 윈도우
	 *       전체를 노출)과 서로 다른 스냅샷 목록을 보여주게 된다.</li>
	 * </ol>
	 */
	public static List<TrackingItemResponse.SnapshotResponse> mergeSnapshots(
			List<TrackingItemResponse.SnapshotResponse> legacy,
			List<TrackingItemResponse.SnapshotResponse> brand) {
		if (brand == null || brand.isEmpty()) {
			return legacy == null ? List.of() : legacy;
		}
		if (legacy == null || legacy.isEmpty()) {
			return brand;
		}

		Map<String, TrackingItemResponse.SnapshotResponse> byDate = new TreeMap<>();
		for (TrackingItemResponse.SnapshotResponse s : legacy) {
			byDate.put(dateKey(s.date()), withNormalizedDate(s));
		}
		for (TrackingItemResponse.SnapshotResponse s : brand) {
			byDate.merge(dateKey(s.date()), withNormalizedDate(s), PerformanceContentAssembler::mergeOne);
		}
		return List.copyOf(byDate.values());
	}

	/** Map.merge 계약상 첫 인자가 기존 값(레거시), 둘째가 새 값(브랜드)이다. */
	private static TrackingItemResponse.SnapshotResponse mergeOne(TrackingItemResponse.SnapshotResponse legacy,
			TrackingItemResponse.SnapshotResponse brand) {
		logConflicts(legacy, brand);
		return new TrackingItemResponse.SnapshotResponse(
				legacy.date(),
				pick(legacy.views(), brand.views()),
				pick(legacy.likes(), brand.likes()),
				legacy.likesHidden() || brand.likesHidden(),
				pick(legacy.comments(), brand.comments()),
				pick(legacy.saves(), brand.saves()),
				pick(legacy.shares(), brand.shares()),
				legacy.sharesHidden() || brand.sharesHidden(),
				pick(legacy.reposts(), brand.reposts()));
	}

	private static Long pick(Long legacy, Long brand) {
		return brand != null ? brand : legacy;
	}

	/** 양쪽 다 값이 있고 서로 다른 지표는 debug 로그로 남긴다(스펙 §7-1 — 산지 간 관측 차이 추적용). */
	private static void logConflicts(TrackingItemResponse.SnapshotResponse legacy,
			TrackingItemResponse.SnapshotResponse brand) {
		if (!log.isDebugEnabled()) {
			return;
		}
		List<String> conflicts = new ArrayList<>();
		addConflict(conflicts, "views", legacy.views(), brand.views());
		addConflict(conflicts, "likes", legacy.likes(), brand.likes());
		addConflict(conflicts, "comments", legacy.comments(), brand.comments());
		addConflict(conflicts, "saves", legacy.saves(), brand.saves());
		addConflict(conflicts, "shares", legacy.shares(), brand.shares());
		addConflict(conflicts, "reposts", legacy.reposts(), brand.reposts());
		if (!conflicts.isEmpty()) {
			log.debug("스냅샷 지표 충돌 — 브랜드 값 채택 date={}, {}", legacy.date(), String.join(", ", conflicts));
		}
	}

	private static void addConflict(List<String> sink, String metric, Long legacy, Long brand) {
		if (legacy != null && brand != null && !legacy.equals(brand)) {
			sink.add(metric + " 레거시=" + legacy + " 브랜드=" + brand);
		}
	}

	private static TrackingItemResponse.SnapshotResponse withNormalizedDate(
			TrackingItemResponse.SnapshotResponse s) {
		String normalized = dateOf(s.date());
		if (normalized == null || normalized.equals(s.date())) {
			return s;
		}
		return new TrackingItemResponse.SnapshotResponse(normalized, s.views(), s.likes(), s.likesHidden(),
				s.comments(), s.saves(), s.shares(), s.sharesHidden(), s.reposts());
	}

	/** TreeMap은 null 키를 받지 않는다 — 날짜 없는 스냅샷(도달 불가)은 빈 키로 접어 맨 앞에 둔다. */
	private static String dateKey(String date) {
		String normalized = dateOf(date);
		return normalized == null ? "" : normalized;
	}

	// ---------- 공용 ----------

	/**
	 * 레거시 응답에서 shortcode 복원 — post.url 경로 세그먼트({@code /p/}·{@code /reel/}·{@code /reels/})
	 * 에서 뽑는다. share 단축 링크처럼 형식이 다르면 null이다(그 콘텐츠는 item.id로만 식별된다).
	 */
	public static String shortcodeOf(String postUrl) {
		if (postUrl == null) {
			return null;
		}
		Matcher matcher = SHORTCODE_PATTERN.matcher(postUrl);
		return matcher.find() ? matcher.group(1) : null;
	}

	/**
	 * 업로드 날짜(KST) — {@code uploadedAt}은 산지에 따라 날짜(레거시)와 타임스탬프(tagged)가 섞여
	 * 들어와서 앞 10자만 본다. 정렬·업로드 기간 필터(Task 10)의 공용 키다.
	 */
	public static LocalDate uploadedOn(PerformanceContentResponse content) {
		PerformancePostResponse post = content.item().post();
		String date = post == null ? null : dateOf(post.uploadedAt());
		if (date == null) {
			return null;
		}
		try {
			return LocalDate.parse(date);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** 날짜·타임스탬프 혼재 문자열의 앞 10자(YYYY-MM-DD). 형식 미달은 null. */
	private static String dateOf(String raw) {
		return raw == null || raw.length() < 10 ? null : raw.substring(0, 10);
	}

	/** 스냅샷은 날짜 오름차순 계약이라 마지막이 최신이다. */
	private static TrackingItemResponse.SnapshotResponse latestOf(
			List<TrackingItemResponse.SnapshotResponse> snapshots) {
		return snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
	}

	private static Long commentsTotal(List<TrackingItemResponse.SnapshotResponse> snapshots) {
		TrackingItemResponse.SnapshotResponse latest = latestOf(snapshots);
		return latest == null ? null : latest.comments();
	}

	/**
	 * 댓글 숨김 = 스냅샷은 있는데 댓글 수가 비어 있는 상태(스냅샷 자체가 없으면 "아직 모름").
	 * 반드시 {@code commentsTotal}과 <b>같은 스냅샷 목록</b>에서 유도해야 한다 — 브랜드 관측만
	 * 단독으로 보면 "레거시가 센 댓글 5건이 실렸는데 hidden=true"인 모순이 난다(브랜드 스냅샷의
	 * comments가 null이어도 병합 결과엔 레거시 값이 남기 때문). 형제 엔드포인트
	 * {@code BrandPostAssembler.commentsHidden}과 같은 정의다.
	 */
	private static boolean commentsHidden(List<TrackingItemResponse.SnapshotResponse> snapshots) {
		TrackingItemResponse.SnapshotResponse latest = latestOf(snapshots);
		return latest != null && latest.comments() == null;
	}

	/** 대시보드의 "마지막 수집"은 두 파이프라인 중 늦은 쪽이다(레거시 02:00 / 브랜드 스윕 03:00). */
	private static OffsetDateTime lastCollectedAt(OffsetDateTime legacy, OffsetDateTime brand) {
		if (legacy == null) {
			return brand;
		}
		return brand == null || brand.isBefore(legacy) ? legacy : brand;
	}

	/**
	 * 조립 결과(필터 전 전량) — Task 10 컨트롤러가 필터·정렬·meta를 얹는다.
	 *
	 * @param competitorBrandAccountIds 경쟁사 구독의 brandAccountId 집합(08-12) — 성과 요약이 경쟁사
	 *        숫자로 오염되지 않도록 컨트롤러가 기본 필터에 쓴다. 브랜드 미귀속(individual) 콘텐츠는
	 *        이 집합에 들 수 없어 기본 범위에 그대로 남는다. <b>monitoring 비활성 환경에서도 채워진다</b>
	 *        — 그 환경에도 direct 콘텐츠는 브랜드에 귀속돼 실리기 때문이다. 활성 링크 0건이면 빈 집합.
	 */
	public record Assembled(List<PerformanceContentResponse> contents, OffsetDateTime lastCollectedAt,
			Set<String> competitorBrandAccountIds) {
	}

	/** 브랜드 풀 조회 결과 — shortcode 키 브랜드 풀(tagged ∪ direct) 전량 + 브랜드 스윕 시각. */
	private record BrandPool(Map<String, BrandPostResponse> byShortcode, OffsetDateTime lastSweptAt) {

		static final BrandPool EMPTY = new BrandPool(Map.of(), null);
	}
}
