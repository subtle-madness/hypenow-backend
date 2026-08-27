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
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.monitoring.ItemStatus;
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
	/** 최신 스냅샷 프로젝션의 매체 구분 — 피드는 views를 null로 접는다({@code snapshotOf} 서빙 규칙). */
	private static final String CONTENT_TYPE_REELS = "REELS";

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

	// ---------- 인덱스 패스(2026-08-27 목록 최적화 설계 §1-2) ----------

	/**
	 * 대시보드 인덱스 패스(경량) — 필터·정렬·statusCounts·페이지 슬라이스·집계가 필요로 하는 판정값
	 * ({@link DashboardRef})만 만든다. {@link #assembleSlim}의 지배 비용(브랜드 풀 <b>전량</b> 풀
	 * 조립 — 게시물당 최대 365행 스냅샷 시계열 + 표시 메타 배치)이 여기서 사라진다: 브랜드 풀은
	 * 판정 컬럼 1쿼리({@link BrandReadRepository#findBrandPostIndex}) + 게시물별 최신 스냅샷 1행
	 * 프로젝션({@link BrandReadRepository#findLatestSnapshotsForBrand})만 읽는다.
	 *
	 * <p>구성은 세 갈래이고, <b>판정·병합 의미론을 현행과 그대로 보존하는 분해</b>가 핵심이다:
	 * <ol>
	 *   <li><b>레거시 계열은 현행 전량 조립 유지</b>(유저당 최대 33행 — 08-12 실측 6ms). 조립된
	 *       카드에서 ref를 유도하고({@link #refOf}), 카드 자체는 하이드레이트가 재사용한다.</li>
	 *   <li><b>레거시와 겹치는 풀 코드만 풀 하이드레이트</b> — 겹침은 레거시 건수로 유계다.
	 *       {@link BrandPostAssembler#hydrate}로 받은 풀 카드를 현행과 같은 {@link #fromLegacy}에
	 *       태워 스냅샷 병합·협찬 승격·additionalSources·귀속을 그대로 수행한다. ref를 그 병합 카드에서
	 *       유도하므로 "지표별 병합된 최신 스냅샷"이 재구현 없이 보존된다.</li>
	 *   <li><b>나머지 풀 전량은 경량 ref 직조</b>({@link #refOfPoolRow}) — 파생 규칙은
	 *       {@link #fromBrandPost}와 같은 함수를 공유한다(판정 함수 이원화 금지).</li>
	 * </ol>
	 *
	 * <p>브랜드 표면({@link BrandPostAssembler#indexForBrand})과 다른 세 가지를 그대로 승계한다:
	 * scope=ALL(정산 전 포함 — 빼면 지표 과소 계상), 커버리지 클램프 on(실수집 범위만 집계),
	 * 노출 필터 + own-first 다계정 병합. {@link #loadBrandPool} 주석 참조.
	 */
	public DashboardIndex index(long userId) {
		TrackingItemAssembler.AssembledList legacy = trackingItemAssembler.assembleList(userId);
		// 활성 링크는 monitoring 게이트 밖에서 한 번 읽는다(08-12) — assemble과 같은 이유.
		List<BrandLinkRow> links = linkRepository.findAllActiveByUser(userId);
		Set<String> competitorIds = competitorBrandAccountIds(links);
		Map<Long, CampaignRow> campaignsById = campaignRepository.findByUser(userId).stream()
				.collect(Collectors.toMap(CampaignRow::id, Function.identity()));
		PoolIndex pool = loadPoolIndex(userId, links);

		// 레거시 아이템의 shortcode와 겹침 코드(브랜드별)를 먼저 확정한다 — 하이드레이트는 브랜드당 1회다.
		Map<String, String> codeByItemId = new LinkedHashMap<>();
		Map<String, Set<String>> overlapCodesByBrand = new LinkedHashMap<>();
		for (TrackingItemResponse item : legacy.items()) {
			String shortcode = shortcodeOf(item.post() == null ? null : item.post().url());
			if (shortcode == null) {
				continue;   // 게시물이 아직 없는 아이템(collecting·detecting) — item.id로만 식별된다.
			}
			codeByItemId.put(item.id(), shortcode);
			PoolEntry entry = pool.byCode().get(shortcode);
			if (entry != null) {
				// 같은 shortcode를 가리키는 레거시 아이템이 둘이어도 하이드레이트는 코드당 1회다.
				overlapCodesByBrand.computeIfAbsent(entry.brandAccountId(), k -> new LinkedHashSet<>())
						.add(shortcode);
			}
		}
		Map<String, BrandPostResponse> overlapByCode = hydrateOverlaps(userId, pool, overlapCodesByBrand);

		Map<String, PerformanceContentResponse> legacyCards = new LinkedHashMap<>();
		List<DashboardRef> refs = new ArrayList<>(legacy.items().size() + pool.byCode().size());
		for (TrackingItemResponse item : legacy.items()) {
			String shortcode = codeByItemId.get(item.id());
			// 목록·비교 표면 계약대로 댓글 없이 조립한다(08-12 슬림) — 단건 조회는 assemble이 맡는다.
			PerformanceContentResponse card = fromLegacy(item, shortcode,
					shortcode == null ? null : overlapByCode.get(shortcode), false);
			legacyCards.put(card.item().id(), card);
			refs.add(refOf(card));
		}

		// 풀 전용 = 풀에 있는데 레거시가 대표하지 않는 코드. 소비 판정은 <b>풀 소속</b> 기준이다
		// (하이드레이트가 어떤 이유로 카드를 못 만들어도 같은 콘텐츠가 두 번 실리지 않게).
		Set<String> legacyCodes = Set.copyOf(codeByItemId.values());
		List<String> poolOnlyCodes = pool.byCode().keySet().stream()
				.filter(code -> !legacyCodes.contains(code))
				.toList();
		Map<String, String> brandByCode = new LinkedHashMap<>();
		Map<String, BrandReadRepository.AuthorRow> authorsByCode = resolvePoolAuthors(pool, poolOnlyCodes);
		Map<String, List<String>> campaignIdsByCode = resolvePoolCampaigns(pool, poolOnlyCodes);
		for (String code : poolOnlyCodes) {
			PoolEntry entry = pool.byCode().get(code);
			DashboardIndex.BrandHydration brand = pool.brandsById().get(entry.brandAccountId());
			brandByCode.put(code, entry.brandAccountId());
			refs.add(refOfPoolRow(entry.brandAccountId(), entry.row(), entry.snapshot(), authorsByCode.get(code),
					campaignIdsByCode.getOrDefault(code, List.of()),
					brand.ownedShortCodes().contains(code)));
		}

		// 현행 contents 정렬과 같은 계약 — 업로드 최신순(미상 마지막), 타이브레이크는 contentKey.
		refs.sort(Comparator.comparing(DashboardRef::uploadedOn, Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(DashboardRef::contentKey));
		return new DashboardIndex(userId, List.copyOf(refs),
				lastCollectedAt(legacy.lastCollectedAt(), pool.lastSweptAt()), competitorIds,
				Map.copyOf(legacyCards), Map.copyOf(brandByCode), pool.brandsById(), campaignsById);
	}

	/**
	 * 브랜드 풀 경량 인덱스 — {@link #loadBrandPool}의 순회 구조(own-first · putIfAbsent · 계정 행
	 * 부재 방어 · lastSweptAt max)를 그대로 쓰되, 브랜드당 조회가 판정 컬럼 1쿼리 + 최신 스냅샷
	 * 1쿼리로 줄었다. 클램프·노출 필터는 {@code assembleBrandPosts}가 하던 것을 여기서 한다.
	 */
	private PoolIndex loadPoolIndex(long userId, List<BrandLinkRow> links) {
		if (brandReadRepository.isEmpty() || brandPostAssembler.isEmpty() || links.isEmpty()) {
			return PoolIndex.EMPTY;   // monitoring 비활성이거나 연결 0건 — 레거시 계열만
		}

		Map<String, PoolEntry> byCode = new LinkedHashMap<>();
		Map<String, DashboardIndex.BrandHydration> brandsById = new LinkedHashMap<>();
		OffsetDateTime lastSweptAt = null;
		for (BrandLinkRow link : ownFirst(links)) {
			Optional<BrandAccountRow> found = brandReadRepository.get().findAccount(link.brandId());
			if (found.isEmpty()) {
				log.warn("브랜드 연결의 monitoring 계정 행 부재 — 브랜드 풀 생략 brandId={}", link.brandId());
				continue;
			}
			BrandAccountRow account = found.get();
			String brandAccountId = String.valueOf(account.id());
			// scope=ALL(enrichedOnly=false) — 지표 집계라 정산 전 게시물도 담는다(loadBrandPool 승계).
			List<BrandReadRepository.BrandPostIndexRow> rows = brandReadRepository.get()
					.findBrandPostIndex(account.id(), BrandPostAssembler.windowCutoff(), false);
			// 커버리지 클램프(수집 상한 v2 §7-1) — coveredUntil의 KST 달력일보다 앞선 tagged 행 제외,
			// direct 등록 행은 상한 밖이라 면제. assembleBrandPosts의 현행 술어와 같은 식이다.
			LocalDate coveredOn = KstTimestamps.toKstDate(account.coveredUntil());
			if (coveredOn != null) {
				rows = rows.stream()
						.filter(r -> r.directRegisteredAt() != null
								|| !KstTimestamps.toKstDate(r.takenAt()).isBefore(coveredOn))
						.toList();
			}
			// 원장 조회는 direct 등록 행이 하나도 없으면 생략한다(현행 관용구).
			boolean hasDirectRegistration = rows.stream().anyMatch(r -> r.directRegisteredAt() != null);
			Set<String> ownedShortCodes = hasDirectRegistration
					? brandPostAssembler.get().directRegisteredShortCodes(userId) : Set.of();
			// 노출 필터(등록자 전용 노출, 08-19 — filterVisibleToUser 동형) + shortcode 중복 방어.
			Map<String, BrandReadRepository.BrandPostIndexRow> visible = new LinkedHashMap<>();
			for (BrandReadRepository.BrandPostIndexRow row : rows) {
				if (row.tagDetectedAt() != null || ownedShortCodes.contains(row.shortCode())) {
					visible.putIfAbsent(row.shortCode(), row);
				}
			}

			// 하이드레이트 재료는 게시물 0건인 브랜드도 실어 둔다 — 페이지 하이드레이트가 계정 행을
			// 다시 읽지 않게 하는 것이 목적이고, lastSweptAt도 게시물 유무와 무관하다(현행과 동일).
			brandsById.put(brandAccountId,
					new DashboardIndex.BrandHydration(account, link.accountType(), ownedShortCodes));
			lastSweptAt = lastCollectedAt(lastSweptAt, account.lastSweptAt());
			if (visible.isEmpty()) {
				continue;
			}

			Map<String, BrandReadRepository.LatestSnapshotRow> latestByCode = new LinkedHashMap<>();
			for (BrandReadRepository.LatestSnapshotRow snapshot : brandReadRepository.get()
					.findLatestSnapshotsForBrand(account.id(), BrandPostAssembler.windowCutoff(), false)) {
				latestByCode.putIfAbsent(snapshot.shortCode(), snapshot);
			}
			for (BrandReadRepository.BrandPostIndexRow row : visible.values()) {
				// own 링크를 먼저 순회하므로 같은 shortcode의 동률은 내 브랜드 쪽으로 풀린다(08-12 규칙).
				byCode.putIfAbsent(row.shortCode(),
						new PoolEntry(brandAccountId, row, latestByCode.get(row.shortCode())));
			}
		}
		return new PoolIndex(byCode, Map.copyOf(brandsById), lastSweptAt);
	}

	/**
	 * 레거시와 겹치는 코드만 브랜드별로 풀 카드 조립 — {@link BrandPostAssembler#hydrate}에 넘길
	 * 인덱스는 이 코드들만 담은 어댑터다(refs·legacyByCode는 대시보드가 쓰지 않는다). 댓글은 싣지
	 * 않는다(목록·비교 표면 계약, 08-12).
	 */
	private Map<String, BrandPostResponse> hydrateOverlaps(long userId, PoolIndex pool,
			Map<String, Set<String>> codesByBrand) {
		if (codesByBrand.isEmpty() || brandPostAssembler.isEmpty()) {
			return Map.of();
		}
		Map<String, BrandPostResponse> byCode = new LinkedHashMap<>();
		for (Map.Entry<String, Set<String>> entry : codesByBrand.entrySet()) {
			DashboardIndex.BrandHydration brand = pool.brandsById().get(entry.getKey());
			List<String> codes = List.copyOf(entry.getValue());
			BrandPostAssembler.BrandPostIndex adapter = new BrandPostAssembler.BrandPostIndex(
					List.of(), Set.copyOf(codes), Map.of(), brand.ownedShortCodes());
			for (BrandPostResponse post : brandPostAssembler.get()
					.hydrate(userId, brand.account(), brand.accountType(), adapter, codes, false)) {
				byCode.putIfAbsent(post.shortcode(), post);
			}
		}
		return byCode;
	}

	/** 풀 전용 코드의 게시자 해석 — 브랜드를 넘어 한 번에 배치한다(조회는 ig_user_id 1회 + 폴백 1회). */
	private Map<String, BrandReadRepository.AuthorRow> resolvePoolAuthors(PoolIndex pool, List<String> codes) {
		if (codes.isEmpty() || brandPostAssembler.isEmpty()) {
			return Map.of();
		}
		List<BrandPostAssembler.AuthorKey> keys = codes.stream()
				.map(code -> pool.byCode().get(code).row())
				.map(row -> new BrandPostAssembler.AuthorKey(row.shortCode(), row.authorIgUserId(),
						row.authorUsername()))
				.toList();
		return brandPostAssembler.get().resolveAuthorsByKeys(keys);
	}

	/** 풀 전용 코드의 캠페인 매핑 — 조회가 브랜드 스코프라 브랜드별로 묶어 1회씩 부른다. */
	private Map<String, List<String>> resolvePoolCampaigns(PoolIndex pool, List<String> codes) {
		if (codes.isEmpty() || brandPostAssembler.isEmpty()) {
			return Map.of();
		}
		Map<String, Set<String>> codesByBrand = new LinkedHashMap<>();
		for (String code : codes) {
			codesByBrand.computeIfAbsent(pool.byCode().get(code).brandAccountId(),
					k -> new LinkedHashSet<>()).add(code);
		}
		Map<String, List<String>> byCode = new LinkedHashMap<>();
		for (Map.Entry<String, Set<String>> entry : codesByBrand.entrySet()) {
			long brandId = pool.brandsById().get(entry.getKey()).account().id();
			byCode.putAll(brandPostAssembler.get().campaignIdsByCode(brandId, entry.getValue()));
		}
		return byCode;
	}

	/**
	 * 레거시 계열 카드 → ref. 최신 스냅샷은 <b>병합 후</b> 목록의 마지막 원소다 — 겹침 콘텐츠의
	 * 지표가 "지표별 병합"을 거친 값이라야 ref 집계가 전량 조립과 일치한다.
	 */
	private static DashboardRef refOf(PerformanceContentResponse content) {
		PerformancePostResponse post = content.item().post();
		TrackingItemResponse.SnapshotResponse latest =
				post == null || post.snapshots().isEmpty() ? null
						: post.snapshots().get(post.snapshots().size() - 1);
		return new DashboardRef(content.item().id(), content.canonicalPostId(), content.source(),
				content.sponsorship(), content.item().status(), uploadedOn(content), content.brandAccountId(),
				content.item().campaignId(), content.item().handle(), content.item().followers(),
				latest == null ? null : latest.views(), latest == null ? null : latest.likes(),
				latest != null && latest.likesHidden(), latest == null ? null : latest.comments(),
				latest != null);
	}

	/**
	 * 브랜드 풀 전용 행 → ref. 파생 규칙은 {@link #fromBrandPost}(및 그 산지인
	 * {@code BrandPostAssembler.brandPost}·{@code snapshotOf})와 같다 — handle은 author_profile
	 * 우선·열거 관측 폴백 후 소문자, views는 피드에서 null, 상태는 unavailable이면 hidden,
	 * campaignId는 campaignIds의 head다.
	 */
	private static DashboardRef refOfPoolRow(String brandAccountId, BrandReadRepository.BrandPostIndexRow row,
			BrandReadRepository.LatestSnapshotRow snap, BrandReadRepository.AuthorRow author,
			List<String> campaignIds, boolean registeredByUser) {
		String username = author != null && author.username() != null ? author.username() : row.authorUsername();
		String handle = username == null ? "" : username.toLowerCase(Locale.ROOT);
		boolean reels = snap != null && CONTENT_TYPE_REELS.equalsIgnoreCase(snap.contentType());
		return new DashboardRef(SYNTHETIC_ID_PREFIX + row.shortCode(), row.shortCode(),
				BrandPostAssembler.resolveSource(row.tagDetectedAt(), row.directRegisteredAt(), registeredByUser),
				BrandSponsorshipClassifier.classify(row.isPaidPartnership(), row.caption()),
				row.unavailableAt() != null ? ItemStatus.HIDDEN : ItemStatus.TRACKING,
				KstTimestamps.toKstDate(row.takenAt()), brandAccountId,
				campaignIds.isEmpty() ? null : campaignIds.get(0), handle,
				author == null ? null : author.followers(),
				snap == null || !reels ? null : snap.views(),
				snap == null ? null : snap.likes(), snap != null && snap.likesHidden(),
				snap == null ? null : snap.comments(), snap != null);
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
				previousDayValues(snapshots), commentsTotal(snapshots), commentsHidden(snapshots),
				comments.size(), comments);
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
				List.of(), post.thumbnailUrl(), null, post.snapshots(), previousDayValues(post.snapshots()),
				post.commentsTotal(), post.commentsHidden(), post.commentsCollectedCount(), post.recentComments());
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

	/** 직전 스냅샷(마지막에서 두 번째)의 지표 3종 — 목록 카드 증가분 표기 재료(2026-08-27). 2개 미만이면 null. */
	static PerformanceContentResponse.PreviousDayValues previousDayValues(
			List<TrackingItemResponse.SnapshotResponse> snapshots) {
		if (snapshots == null || snapshots.size() < 2) {
			return null;
		}
		TrackingItemResponse.SnapshotResponse prev = snapshots.get(snapshots.size() - 2);
		return new PerformanceContentResponse.PreviousDayValues(prev.views(), prev.likes(), prev.comments());
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

	/** 대시보드 콘텐츠 1건의 경량 참조 — 필터·statusCounts·정렬·페이지·집계의 판정값 전부. */
	public record DashboardRef(String contentKey, String shortcode, String source, String sponsorship,
			String status, LocalDate uploadedOn, String brandAccountId, String campaignId,
			String handle, Long followers, Long latestViews, Long latestLikes, boolean latestLikesHidden,
			Long latestComments, boolean hasSnapshots) {
	}

	/**
	 * 인덱스 패스 결과({@link #index}) — refs 외 나머지는 페이지 하이드레이트가 재사용하는 재료다.
	 * 인덱스가 이미 읽은 것(레거시 카드·계정 행·등록 원장·캠페인)을 다시 읽지 않게 실어 나른다.
	 *
	 * @param legacyCards contentKey(=item.id) → 조립 완료 카드. 겹침 병합분이 이미 반영돼 있다.
	 * @param brandByCode 풀 전용 shortcode → brandAccountId(겹침 코드는 레거시 카드가 정본이라 없다).
	 * @param brandsById brandAccountId → 하이드레이트 재료. 게시물 0건인 연결 브랜드도 담긴다.
	 * @param campaignsById 캠페인 id → 행(합성 아이템의 campaignName 산지).
	 */
	public record DashboardIndex(long userId, List<DashboardRef> refs, OffsetDateTime lastCollectedAt,
			Set<String> competitorBrandAccountIds,
			Map<String, PerformanceContentResponse> legacyCards,
			Map<String, String> brandByCode,
			Map<String, BrandHydration> brandsById,
			Map<Long, CampaignRow> campaignsById) {

		public record BrandHydration(BrandAccountRow account, String accountType, Set<String> ownedShortCodes) {
		}
	}

	/** 풀 전용 ref 직조 입력 1건 — 귀속 브랜드 + 판정 컬럼 행 + (있으면) 최신 스냅샷 1행. */
	private record PoolEntry(String brandAccountId, BrandReadRepository.BrandPostIndexRow row,
			BrandReadRepository.LatestSnapshotRow snapshot) {
	}

	/** 브랜드 풀 경량 인덱스 — own-first putIfAbsent로 확정된 shortcode 키 풀 + 하이드레이트 재료. */
	private record PoolIndex(Map<String, PoolEntry> byCode,
			Map<String, DashboardIndex.BrandHydration> brandsById, OffsetDateTime lastSweptAt) {

		static final PoolIndex EMPTY = new PoolIndex(Map.of(), Map.of(), null);
	}
}
