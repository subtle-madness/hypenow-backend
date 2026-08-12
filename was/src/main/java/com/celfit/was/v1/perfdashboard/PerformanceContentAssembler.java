package com.celfit.was.v1.perfdashboard;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 성과 대시보드 통합 조립(스펙 §7-1) — 콘텐츠 3계열을 shortcode 하나로 합친다.
 *
 * <ul>
 *   <li><b>individual</b>: 레거시 추적 아이템 중 브랜드 매핑이 없는 것(개인 캠페인 등록분).</li>
 *   <li><b>direct</b>: 레거시 아이템 중 {@code app.brand_direct_posts} 매핑이 있는 것.</li>
 *   <li><b>tagged</b>: 브랜드 스윕이 발견한 태그 게시물({@link BrandPostAssembler}).</li>
 * </ul>
 *
 * <p>대표 산지는 하나다 — 우선순위 <b>individual &gt; direct &gt; tagged</b>(설계 결정 7).
 * 레거시 아이템이 있으면 그 아이템이 본체가 되고(상태·기간·캠페인 전부 레거시 정본), 겹치는 tagged
 * 관측은 {@code additionalSources}로 남기면서 <b>스냅샷만</b> 지표별로 병합한다. 레거시 없이 tagged만
 * 있는 게시물은 레거시 행이 없으므로 아이템을 합성한다({@code "bt_"+shortcode}).
 *
 * <p>필터·정렬·statusCounts는 여기서 하지 않는다(컨트롤러 몫) — 이 클래스는 필터 전 "전량"을
 * 업로드 최신순으로 돌려준다.
 *
 * <p>monitoring 서브시스템이 꺼진 환경에선 브랜드 계열 빈이 아예 없다 — 그래서 브랜드 의존은
 * {@link Optional}이고, 그 경우 tagged 계열을 건너뛴다(대시보드 표면은 살아 있어야 한다).
 * 다만 <b>레거시만 조립하는 것은 아니다</b>: 직접 등록 매핑(app.brand_direct_posts)과 브랜드 연결
 * (app.brand_monitorings)은 app DataSource라 그 환경에서도 살아 있어, direct 콘텐츠의 브랜드 귀속과
 * 구독 타입(own/competitor) 판정은 그대로 성립한다(08-12).
 */
@Component
public class PerformanceContentAssembler {

	/** 대표 산지 값 공간 — 컨트롤러 필터(Task 10)가 리터럴을 다시 적지 않게 상수로 노출한다. */
	public static final String SOURCE_INDIVIDUAL = "individual";
	public static final String SOURCE_DIRECT = "direct";
	public static final String SOURCE_TAGGED = "tagged";

	/** tagged-only 합성 아이템의 id 접두 — 레거시 숫자 id와 절대 충돌하지 않게 한다(설계 결정 7). */
	private static final String SYNTHETIC_ID_PREFIX = "bt_";
	/** 합성 아이템의 추적 기간 — 브랜드 표시 윈도우(90일)와 같은 값(스펙 §7-1). */
	private static final int TAGGED_TRACKING_DAYS = 90;
	private static final String MODE_URL = "url";
	private static final String STATUS_TRACKING = "tracking";

	/** 레거시 응답엔 shortcode 필드가 없어 post.url 경로 세그먼트에서 뽑는다(스펙 §7-1). */
	private static final Pattern SHORTCODE_PATTERN = Pattern.compile("/(?:p|reel|reels)/([A-Za-z0-9_-]+)");

	private static final Logger log = LoggerFactory.getLogger(PerformanceContentAssembler.class);

	private final TrackingItemAssembler trackingItemAssembler;
	private final BrandDirectPostRepository directPostRepository;
	private final BrandLinkRepository linkRepository;
	private final Optional<BrandReadRepository> brandReadRepository;
	private final Optional<BrandPostAssembler> brandPostAssembler;

	public PerformanceContentAssembler(TrackingItemAssembler trackingItemAssembler,
			BrandDirectPostRepository directPostRepository, BrandLinkRepository linkRepository,
			Optional<BrandReadRepository> brandReadRepository, Optional<BrandPostAssembler> brandPostAssembler) {
		this.trackingItemAssembler = trackingItemAssembler;
		this.directPostRepository = directPostRepository;
		this.linkRepository = linkRepository;
		this.brandReadRepository = brandReadRepository;
		this.brandPostAssembler = brandPostAssembler;
	}

	/** 필터 전 전량(업로드 최신순, 업로드일 미상은 마지막). Task 10 컨트롤러가 소비한다. */
	public Assembled assemble(long userId) {
		TrackingItemAssembler.AssembledList legacy = trackingItemAssembler.assembleList(userId);
		DirectMapping direct = directMapping(userId);
		// 활성 링크는 monitoring 게이트 <b>밖</b>에서 한 번 읽는다(08-12) — 구독 타입 판정에 필요하고,
		// BrandLinkRepository는 app DataSource라 monitoring 비활성 환경에서도 살아 있다. 직접 등록
		// 매핑(app.brand_direct_posts)도 같은 이유로 게이트 밖이라, 링크를 안 읽으면 경쟁사 브랜드의
		// direct 콘텐츠가 기본 범위에 조용히 섞인다(운영 기본값 MONITORING_ENABLED=false).
		List<BrandLinkRow> links = linkRepository.findAllActiveByUser(userId);
		Set<String> competitorIds = competitorBrandAccountIds(links);
		Tagged tagged = loadTagged(userId, links);

		List<PerformanceContentResponse> contents = new ArrayList<>();
		Set<String> consumedCodes = new LinkedHashSet<>();
		for (TrackingItemResponse item : legacy.items()) {
			String shortcode = shortcodeOf(item.post() == null ? null : item.post().url());
			BrandPostResponse overlap = shortcode == null ? null : tagged.byShortcode().get(shortcode);
			if (overlap != null) {
				consumedCodes.add(shortcode);
			}
			contents.add(fromLegacy(item, shortcode, direct.brandAccountIdFor(item.id(), shortcode), overlap,
					competitorIds));
		}
		for (Map.Entry<String, BrandPostResponse> entry : tagged.byShortcode().entrySet()) {
			if (!consumedCodes.contains(entry.getKey())) {
				contents.add(fromTagged(entry.getValue()));
			}
		}

		contents.sort(Comparator
				.comparing(PerformanceContentAssembler::uploadedOn, Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(c -> c.item().id()));
		return new Assembled(List.copyOf(contents), lastCollectedAt(legacy.lastCollectedAt(), tagged.lastSweptAt()),
				competitorIds);
	}

	// ---------- 레거시 계열 ----------

	/**
	 * 레거시 아이템 1건 → 대시보드 콘텐츠. 아이템 본체(상태·기간·캠페인·핸들)는 전부 레거시가 정본이고,
	 * 겹치는 tagged가 있으면 스냅샷 병합·협찬 승격·additionalSources만 얹는다(스펙 §7-1).
	 *
	 * @param directBrandAccountId 직접 등록 매핑의 브랜드 id 문자열(매핑이 없으면 null) — 이 값의
	 *                             존재 자체가 direct 판정이다.
	 * @param competitorIds 경쟁사 구독의 brandId 집합 — 귀속 동률을 own 쪽으로 푸는 데만 쓴다
	 *                      ({@link #attributedBrandAccountId}).
	 */
	private static PerformanceContentResponse fromLegacy(TrackingItemResponse item, String shortcode,
			String directBrandAccountId, BrandPostResponse overlap, Set<String> competitorIds) {
		PerformancePostResponse post = legacyPost(item, shortcode, overlap);
		return new PerformanceContentResponse(
				new PerformanceItemResponse(item.id(), item.mode(), item.status(), item.handle(),
						item.displayName(), item.profileImageUrl(), item.followers(), item.lastUploadedAt(),
						item.campaignId(), item.campaignName(), item.sourceUrl(), item.registeredAt(),
						item.trackingDays(), item.keywords(), post, item.nextCheckAt()),
				directBrandAccountId != null ? SOURCE_DIRECT : SOURCE_INDIVIDUAL,
				sponsorshipOf(item, overlap),
				// 게시물이 아직 없는 아이템(collecting·detecting·not_uploaded)은 shortcode 자체가 없다 —
				// 그 콘텐츠는 item.id로만 식별된다(스펙 §7-1).
				shortcode,
				overlap == null ? List.of() : List.of(SOURCE_TAGGED),
				attributedBrandAccountId(directBrandAccountId, overlap, competitorIds));
	}

	/**
	 * 브랜드 귀속 결정 — 브랜드 소속은 tagged 관측만의 속성이 아니다. direct는 매핑 자체가 "이 게시물은
	 * 이 브랜드 소속"이라는 선언이라 tagged 관측이 아직 없어도 채운다(brandAccountId 필터가 자기 브랜드의
	 * direct를 떨구면 안 된다). 둘 다 있으면 <b>관측값(tagged)을 우선</b>한다 — 브랜드 스윕이 더 늦게
	 * 수집한 원천이라 스냅샷 최신도가 높다.
	 *
	 * <p><b>단 하나의 예외</b>(08-12): direct가 내 브랜드(own 구독)를 가리키는데 겹치는 tagged 관측이
	 * 경쟁사 브랜드면 <b>direct(own) 귀속을 지킨다</b>. 내가 내 브랜드로 직접 등록한 게시물이 "경쟁사
	 * 계정에도 태그돼 있었다"는 이유만으로 내 성과 요약·statusCounts에서 사라지면 안 된다
	 * (스펙 §5의 기본 범위는 "own 브랜드 콘텐츠 + individual"이다).
	 *
	 * <p>{@link #ownFirst}가 tagged끼리의 동률을 own 쪽으로 푸는 것과 같은 규칙을 한 층 위(direct 대
	 * tagged)에 적용한 것이다. 이유도 같다 — 귀속이 이제 <b>표시</b>가 아니라 <b>범위</b>를 정하기
	 * 때문이다. 양쪽이 같은 타입인 경우는 손대지 않는다(관측값 우선의 근거가 그대로 유효하다).
	 */
	private static String attributedBrandAccountId(String directBrandAccountId, BrandPostResponse overlap,
			Set<String> competitorIds) {
		if (overlap == null) {
			return directBrandAccountId;
		}
		if (directBrandAccountId != null && !competitorIds.contains(directBrandAccountId)
				&& competitorIds.contains(overlap.brandAccountId())) {
			return directBrandAccountId;
		}
		return overlap.brandAccountId();
	}

	private static PerformancePostResponse legacyPost(TrackingItemResponse item, String shortcode,
			BrandPostResponse overlap) {
		TrackingItemResponse.TrackedPostResponse post = item.post();
		if (post == null) {
			return null;
		}
		List<TrackingItemResponse.SnapshotResponse> snapshots =
				overlap == null ? post.snapshots() : mergeSnapshots(post.snapshots(), overlap.snapshots());
		// 댓글은 병합하지 않는다(두 산지의 id 공간·정렬이 달라 섞으면 순서가 무의미해진다) — 레거시가
		// 한 건도 못 모은 경우에만 브랜드 수집분으로 메운다(빈 목록을 그대로 내보내는 것보다 낫다).
		List<TrackingItemResponse.PostCommentResponse> comments =
				!post.recentComments().isEmpty() || overlap == null ? post.recentComments() : overlap.recentComments();

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

	// ---------- tagged 계열 ----------

	/**
	 * tagged-only 콘텐츠 — 레거시 행이 없어 아이템을 합성한다(설계 결정 7). id 접두 {@code bt_}는
	 * 레거시 숫자 id와의 충돌을 막고, {@code canonicalPostId}는 순수 shortcode 그대로다.
	 * 등록일은 브랜드가 이 게시물을 처음 본 날(first_seen), 추적 기간은 표시 윈도우와 같은 90일이다.
	 */
	private static PerformanceContentResponse fromTagged(BrandPostResponse post) {
		String handle = post.authorUsername() == null ? "" : post.authorUsername().toLowerCase(Locale.ROOT);
		String displayName = post.authorFullName() == null || post.authorFullName().isBlank()
				? handle : post.authorFullName();

		PerformancePostResponse dashboardPost = new PerformancePostResponse(post.postUrl(), post.shortcode(),
				post.contentType(), post.takenAt(), post.caption(),
				// 브랜드 태그 게시물은 키워드 감지 경로가 아니다(브랜드 계정 태그가 곧 편입 사유).
				List.of(), post.thumbnailUrl(), null, post.snapshots(), post.commentsTotal(),
				post.commentsHidden(), post.commentsCollectedCount(), post.recentComments());

		return new PerformanceContentResponse(
				new PerformanceItemResponse(SYNTHETIC_ID_PREFIX + post.shortcode(), MODE_URL, STATUS_TRACKING,
						handle, displayName, post.authorProfilePicUrl(), post.authorFollowers(),
						// 게시자의 마지막 업로드 시각은 브랜드 파이프라인이 관측하지 않는다(프로필 스윕 대상이 아님).
						null, null, null, post.postUrl(), dateOf(post.trackingStartedAt()), TAGGED_TRACKING_DAYS,
						null, dashboardPost, null),
				SOURCE_TAGGED, post.sponsorship(), post.shortcode(), List.of(), post.brandAccountId());
	}

	/**
	 * 직접 등록 매핑 — shortcode와 레거시 아이템 id <b>양쪽</b>으로 판정한다.
	 *
	 * <p>shortcode만 보면 아직 게시물이 확정되지 않은 직접 등록분(collecting — post가 null이라
	 * shortcode를 만들 수 없다)이 첫 수집 전까지 individual로 표시된다. 매핑 행에 이미
	 * {@code monitoring_item_id}가 있으므로 id로도 같은 판정이 가능하고, 두 키는 같은 매핑을
	 * 가리키므로 합집합을 써도 individual → direct 방향으로만 바뀐다(오탐 없음).
	 *
	 * <p>매핑의 {@code brand_id}까지 관통시킨다 — direct 콘텐츠의 {@code brandAccountId}는 tagged
	 * 관측 없이도 확정되는 값이기 때문이다(FE PerformanceContent 계약).
	 */
	private DirectMapping directMapping(long userId) {
		Map<String, String> byShortCode = new LinkedHashMap<>();
		Map<String, String> byItemId = new LinkedHashMap<>();
		for (BrandDirectPostRepository.Row row : directPostRepository.findByUser(userId)) {
			String brandAccountId = String.valueOf(row.brandId());
			byShortCode.putIfAbsent(row.shortCode(), brandAccountId);
			byItemId.putIfAbsent(String.valueOf(row.monitoringItemId()), brandAccountId);
		}
		return new DirectMapping(byShortCode, byItemId);
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
	 * 활성 브랜드 연결이 있을 때만 브랜드 계열을 조립한다 — 없으면 monitoring DB를 아예 건드리지 않는다.
	 * 다계정(08-07 개정)은 연결 순서대로 병합하되 own 묶음이 먼저다({@link #ownFirst}) — 같은 shortcode가
	 * 여러 브랜드에 태그돼 있으면 내 브랜드가, 같은 타입 안에서는 먼저 연결한 브랜드가 이긴다
	 * (putIfAbsent). lastSweptAt은 브랜드들 중 가장 늦은 값이라 순회 순서와 무관하다.
	 *
	 * @param links 호출부가 이미 읽어 둔 활성 링크 — 여기서 다시 조회하지 않는다(monitoring이 켜져 있든
	 *              꺼져 있든 링크 조회는 요청당 한 번이다).
	 */
	private Tagged loadTagged(long userId, List<BrandLinkRow> links) {
		if (brandReadRepository.isEmpty() || brandPostAssembler.isEmpty() || links.isEmpty()) {
			return Tagged.EMPTY;   // monitoring 비활성이거나 연결 0건 — 레거시 계열만
		}

		Map<String, BrandPostResponse> byShortcode = new LinkedHashMap<>();
		OffsetDateTime lastSweptAt = null;
		for (BrandLinkRow link : ownFirst(links)) {
			Optional<BrandAccountRow> account = brandReadRepository.get().findAccount(link.brandId());
			if (account.isEmpty()) {
				// 연결은 살아 있는데 monitoring 쪽 계정 행이 없는 상태 — 대시보드를 죽이지 않고 그 브랜드만 뺀다.
				log.warn("브랜드 연결의 monitoring 계정 행 부재 — tagged 생략 userId={}, brandId={}",
						userId, link.brandId());
				continue;
			}
			for (BrandPostResponse post : brandPostAssembler.get().assembleTagged(account.get())) {
				byShortcode.putIfAbsent(post.shortcode(), post);
			}
			lastSweptAt = lastCollectedAt(lastSweptAt, account.get().lastSweptAt());
		}
		return new Tagged(byShortcode, lastSweptAt);
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

	/** 직접 등록 매핑 색인 — 같은 매핑을 shortcode·아이템 id 두 키로 조회한다(값은 브랜드 id 문자열). */
	private record DirectMapping(Map<String, String> byShortCode, Map<String, String> byItemId) {

		/** 매핑된 브랜드 id 문자열, 직접 등록분이 아니면 null. */
		String brandAccountIdFor(String itemId, String shortcode) {
			String byId = byItemId.get(itemId);
			return byId != null || shortcode == null ? byId : byShortCode.get(shortcode);
		}
	}

	/** 브랜드 계열 조회 결과 — shortcode 키 tagged 전량 + 브랜드 스윕 시각. */
	private record Tagged(Map<String, BrandPostResponse> byShortcode, OffsetDateTime lastSweptAt) {

		static final Tagged EMPTY = new Tagged(Map.of(), null);
	}
}
