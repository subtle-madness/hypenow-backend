package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.AuthorRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandCommentRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandPostMetaRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandSnapshotRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandTaggedPostRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.monitoring.AuthorMask;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * BrandPost 조립(2026-08-18 direct 통합 §3-3) — 브랜드 풀(monitoring {@code brand_tagged_post})
 * 한 산지에서 {@link BrandPostResponse}를 조립한다. tagged·direct는 더 이상 별도 조립 함수·병합
 * 단계를 갖지 않는다 — 두 값 모두 같은 행({@code tag_detected_at}·{@code direct_registered_at})의
 * 파생값이라 조립이 한 벌로 줄었다(설계 §결정 1, §1의 "겹침 게시물 direct 셰이프 고정" 해악이
 * 구조적으로 소멸).
 *
 * <p><b>과도기 폴백</b>(설계 §4-1, C 단계에서 제거): {@code app.brand_direct_posts.migrated_at IS
 * NULL}인 매핑(이관 잡이 아직 못 옮긴 구 direct 등록)만 레거시 파이프라인({@link TrackingItemAssembler})
 * 조립을 빌려 결과에 얹는다({@link #assembleLegacyPending}). 이관 잡(M2)이 진행되는 만큼 자연히
 * 비어가는 임시 경로이고, 그 안에서만 레거시 조립 의존을 유지한다.
 *
 * <p>조립 규칙({@link #brandPost}·{@link #snapshotOf}·{@link #commentOf})은 전부 정적 순수 함수라
 * DB 없이 단위 테스트한다. 인스턴스 메서드는 배치 조회 배선만 담당한다.
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class BrandPostAssembler {

	static final String SOURCE_TAGGED = "tagged";
	static final String SOURCE_DIRECT = "direct";

	private static final Logger log = LoggerFactory.getLogger(BrandPostAssembler.class);

	/**
	 * 표시 윈도우 365일(크롤링 정책 v1 08-09 — 수집 편입 컷과 같은 깊이, 상한 없음). 직접 등록(§6-4)의
	 * "이미 브랜드가 들고 있는 게시물" 판정도 같은 <b>창</b>을 봐야 해서 패키지 공개다
	 * ({@link V1BrandDirectPostService}). 창은 같지만 정산 게이트는 다르다 — 그쪽은 중복 판정이라
	 * 미정산분까지 전량을 본다({@link BrandPostScope} 참조). direct 등록 행은 이 창의 예외다 — 등록
	 * 시점이 아무리 오래돼도 표시된다({@code BrandReadRepository.findBrandPostsInWindow}).
	 */
	static final int WINDOW_DAYS = 365;
	/** 댓글 서빙 상한 — monitoring 수집 상한(3페이지 45건)과 같은 수로 맞춘다(레거시 COMMENT_LIMIT 동형). */
	private static final int COMMENT_LIMIT = 45;

	private static final String CONTENT_TYPE_REELS = "REELS";
	private static final String REELS = "reels";
	private static final String FEED = "feed";
	private static final String PROFILE_URL_PREFIX = "https://www.instagram.com/";
	/** 브랜드 풀 게시물은 전부 나이 티어 정책으로 계속 수집되는 활성 상태라 항상 tracking이다(설계 §3-3). */
	private static final String TRACKING = "tracking";
	private static final String HIDDEN = "hidden";

	private final BrandReadRepository brandReadRepository;
	private final BrandPostCampaignRepository postCampaignRepository;
	/** 과도기 폴백({@link #assembleLegacyPending}) 전용 — TODO(contract) C 단계에서 제거. */
	private final BrandDirectPostRepository directPostRepository;
	/** 과도기 폴백 전용 — TODO(contract) C 단계에서 제거. */
	private final TrackingItemAssembler trackingItemAssembler;
	private final MonitoringItemRepository monitoringItemRepository;
	/** 광고 표기 판정 노출 게이트(스펙 §10-2 드라이런 후 개통) — 기본 false, was 문서화(Task 18)에서 개통 절차 안내. */
	private final boolean exposeAdDisclosure;
	private static final ObjectMapper OM = new ObjectMapper();

	public BrandPostAssembler(BrandReadRepository brandReadRepository,
			BrandPostCampaignRepository postCampaignRepository, BrandDirectPostRepository directPostRepository,
			TrackingItemAssembler trackingItemAssembler, MonitoringItemRepository monitoringItemRepository,
			@Value("${monitoring.brand.ad-disclosure.expose:false}") boolean exposeAdDisclosure) {
		this.brandReadRepository = brandReadRepository;
		this.postCampaignRepository = postCampaignRepository;
		this.directPostRepository = directPostRepository;
		this.trackingItemAssembler = trackingItemAssembler;
		this.monitoringItemRepository = monitoringItemRepository;
		this.exposeAdDisclosure = exposeAdDisclosure;
	}

	// 브랜드 화면(목록·상세·counts)의 진입점이던 assembleForBrand(전량 풀 조립 + 레거시 병합)는
	// 2026-08-27 목록 타임아웃 해소로 indexForBrand + hydrate 2단 조립에 흡수됐다 — 표시 표면
	// 계약(정산분만 ENRICHED_ONLY·커버리지 클램프 없음·풀 우선 병합)은 그 두 메서드가 그대로 승계한다.

	// ---------- 인덱스/하이드레이트(2026-08-27 목록 타임아웃 해소 설계) ----------

	/**
	 * 게시물 1건의 경량 참조 — counts·필터·정렬·페이지 슬라이스가 필요로 하는 판정값만 담는다.
	 * 판정 산지는 풀 조립과 동일 함수({@code resolveSource}·{@code BrandSponsorshipClassifier.classify}·
	 * KST 달력일)라 ref 기반 counts는 전량 풀 조립 counts와 정의상 일치한다.
	 *
	 * @param latestViews performance 정렬 키(피드는 null — {@link #snapshotOf} 서빙 규칙 동형).
	 *                    {@code withViews=false} 인덱스에서는 항상 null이다.
	 */
	public record PostRef(String shortcode, String source, String sponsorship, LocalDate uploadedOn,
			Long latestViews) {
	}

	/**
	 * 인덱스 패스 결과 — refs 외 나머지는 {@link #hydrate}가 재사용하는 재료다: poolCodes는 노출
	 * 필터를 통과한 브랜드 풀 shortcode 집합(하이드레이트의 풀/레거시 분기 기준), legacyByCode는
	 * 과도기 폴백 카드(풀과 겹치는 shortcode는 이미 제외 — 풀 우선 병합 규칙), ownedShortCodes는
	 * 이 유저의 direct 등록 원장(source 파생에 썼던 값을 하이드레이트가 다시 조회하지 않게 실어
	 * 나른다).
	 */
	public record BrandPostIndex(List<PostRef> refs, Set<String> poolCodes,
			Map<String, BrandPostResponse> legacyByCode, Set<String> ownedShortCodes) {
	}

	/**
	 * 인덱스 패스(경량) — 브랜드 풀의 판정 입력 <b>단일 조인 쿼리</b>({@link
	 * BrandReadRepository#findBrandPostIndex}) + (withViews면) 최신 스냅샷 1행 프로젝션만 읽어
	 * {@link PostRef}를 만든다. 스냅샷 시계열·댓글·게시자·표시 메타 배치 조회가 전혀 없고, 무거운
	 * 조립은 {@link #hydrate}가 페이지 코드에만 수행한다. 표시 표면 전용이라 scope는 항상
	 * ENRICHED_ONLY다. 단일 쿼리·최소 컬럼인 이유(만 건대 브랜드의 행×컬럼 매핑이 지배 비용)는
	 * 리포지토리 주석 참조.
	 *
	 * <p>과도기 폴백(레거시 direct)은 유저별 소량·이관으로 소멸 중이라 여기서 통째로 조립해 ref와
	 * 카드 양쪽에 재사용한다 — 풀과 겹치는 shortcode는 풀이 이긴다(기존 병합 규칙 불변).
	 *
	 * @param withViews true면 performance 정렬 키(latestViews)를 채운다 — 그 정렬이 아닐 때는
	 *                  최신 스냅샷 조회 자체를 생략한다(withComments 관용구와 같은 이유).
	 */
	public BrandPostIndex indexForBrand(long userId, BrandAccountRow account, boolean withViews) {
		List<BrandReadRepository.BrandPostIndexRow> allRows = brandReadRepository.findBrandPostIndex(
				account.id(), windowCutoff(), true);
		boolean hasDirectRegistration = allRows.stream().anyMatch(r -> r.directRegisteredAt() != null);
		Set<String> ownedShortCodes = hasDirectRegistration ? directPostRepository.shortCodesByUser(userId)
				: Set.of();

		// 노출 필터(등록자 전용 노출, 08-19 — filterVisibleToUser 동형) + shortcode 중복 방어.
		Map<String, BrandReadRepository.BrandPostIndexRow> poolByCode = new LinkedHashMap<>();
		for (BrandReadRepository.BrandPostIndexRow row : allRows) {
			if (row.tagDetectedAt() != null || ownedShortCodes.contains(row.shortCode())) {
				poolByCode.putIfAbsent(row.shortCode(), row);
			}
		}

		// 피드 views null 서빙 규칙(snapshotOf 동형)을 정렬 키에도 적용한다 — HashMap을 쓰는 이유는
		// null 값(피드)을 담기 위해서다(Collectors.toMap은 null 값에서 NPE).
		Map<String, Long> viewsByCode = new LinkedHashMap<>();
		if (withViews && !poolByCode.isEmpty()) {
			for (BrandReadRepository.LatestSnapshotRow row : brandReadRepository.findLatestSnapshotsForBrand(
					account.id(), windowCutoff(), true)) {
				viewsByCode.put(row.shortCode(),
						CONTENT_TYPE_REELS.equalsIgnoreCase(row.contentType()) ? row.views() : null);
			}
		}

		Map<String, BrandPostResponse> legacyByCode = new LinkedHashMap<>();
		for (BrandPostResponse legacy : assembleLegacyPending(userId, account.id())) {
			if (!poolByCode.containsKey(legacy.shortcode())) {
				legacyByCode.putIfAbsent(legacy.shortcode(), legacy);
			}
		}

		List<PostRef> refs = new ArrayList<>(poolByCode.size() + legacyByCode.size());
		for (BrandReadRepository.BrandPostIndexRow row : poolByCode.values()) {
			refs.add(new PostRef(row.shortCode(),
					resolveSource(row.tagDetectedAt(), row.directRegisteredAt(),
							ownedShortCodes.contains(row.shortCode())),
					BrandSponsorshipClassifier.classify(row.isPaidPartnership(), row.caption()),
					KstTimestamps.toKstDate(row.takenAt()),
					viewsByCode.get(row.shortCode())));
		}
		for (BrandPostResponse legacy : legacyByCode.values()) {
			TrackingItemResponse.SnapshotResponse latest = legacy.latestSnapshot();
			refs.add(new PostRef(legacy.shortcode(), legacy.source(), legacy.sponsorship(), uploadedOn(legacy),
					latest == null ? null : latest.views()));
		}
		return new BrandPostIndex(List.copyOf(refs), poolByCode.keySet(), legacyByCode, ownedShortCodes);
	}

	/**
	 * 하이드레이트 패스(무거움) — 인덱스가 고른 {@code codes}만 풀 카드로 조립한다. 배치 조회
	 * (표시 메타·스냅샷·게시자·캠페인·(withComments면) 댓글)가 전부 codes 스코프라, 페이지네이션이
	 * 걸리면 비용이 페이지 크기에만 비례한다. 반환 순서는 입력 codes 순서다(정렬은 호출부가 ref로
	 * 이미 끝냈다). 인덱스에 없는 코드는 조용히 건너뛴다.
	 *
	 * <p>{@code withComments=false}면 댓글 배치 조회를 아예 돌리지 않고(08-12 관용구) 과도기 폴백
	 * 카드도 {@link BrandPostResponse#withoutRecentComments()}로 비운다 — 목록 표면 계약(FE 요청 1).
	 */
	public List<BrandPostResponse> hydrate(long userId, BrandAccountRow account, String viewerAccountType,
			BrandPostIndex index, List<String> codes, boolean withComments) {
		Set<String> poolCodes = codes.stream()
				.filter(index.poolCodes()::contains)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		// 인덱스는 경량 컬럼만 들고 있어 풀 카드 조립 입력(게시자 id·first_seen 등)은 페이지 코드만
		// 여기서 다시 읽는다 — 페이지 상한(≤100) IN이라 인덱스 프로젝션과 달리 부담이 없다.
		List<BrandTaggedPostRow> posts = poolCodes.isEmpty() ? List.of()
				: brandReadRepository.findBrandPostsByShortCodes(account.id(), poolCodes);

		Map<String, BrandPostResponse> poolCards = new LinkedHashMap<>();
		if (!posts.isEmpty()) {
			Map<String, BrandPostMetaRow> metaByCode = brandReadRepository.findPostMeta(poolCodes).stream()
					.collect(Collectors.toMap(BrandPostMetaRow::shortCode, Function.identity(), (a, b) -> a));
			Map<String, List<BrandSnapshotRow>> snapshotsByCode = brandReadRepository.findSnapshots(poolCodes)
					.stream().collect(Collectors.groupingBy(BrandSnapshotRow::shortCode));
			Map<String, List<BrandCommentRow>> commentsByCode = !withComments ? Map.of()
					: brandReadRepository.findComments(poolCodes, COMMENT_LIMIT).stream()
							.collect(Collectors.groupingBy(BrandCommentRow::shortCode));
			Map<String, AuthorRow> authorsByPost = resolveAuthors(posts);
			Map<String, List<String>> campaignIdsByCode = campaignIdsByCode(account.id(), poolCodes);
			Set<String> seededUsernames = !exposeAdDisclosure ? Set.of() : resolveSeededUsernames(userId);
			for (BrandTaggedPostRow post : posts) {
				poolCards.put(post.shortCode(), brandPost(account.id(), post, metaByCode.get(post.shortCode()),
						authorsByPost.get(post.shortCode()),
						snapshotsByCode.getOrDefault(post.shortCode(), List.of()),
						commentsByCode.getOrDefault(post.shortCode(), List.of()), account.lastSweptAt(),
						campaignIdsByCode.getOrDefault(post.shortCode(), List.of()), exposeAdDisclosure,
						seededUsernames, index.ownedShortCodes().contains(post.shortCode()), viewerAccountType));
			}
		}

		List<BrandPostResponse> out = new ArrayList<>(codes.size());
		for (String code : codes) {
			BrandPostResponse card = poolCards.get(code);
			if (card == null) {
				card = index.legacyByCode().get(code);
			}
			if (card == null) {
				continue;
			}
			out.add(withComments ? card : card.withoutRecentComments());
		}
		return out;
	}

	// ---------- 브랜드 풀 ----------

	/**
	 * 컷은 KST 달력일 기준이다 — 인스턴트에서 365일을 빼면 요청 시각에 따라 경계일이 들쭉날쭉해진다.
	 *
	 * <p>공개 이유: 성과 대시보드 인덱스(2026-08-27)가 같은 창을 봐야 한다 — 패키지 밖에서 식을
	 * 재계산하면 창 정책이 이원화된다(진단 하니스의 복사본도 이 정본 호출로 정리했다).
	 */
	public static OffsetDateTime windowCutoff() {
		return LocalDate.now(KstTimestamps.KST).minusDays(WINDOW_DAYS)
				.atStartOfDay(KstTimestamps.KST).toOffsetDateTime();
	}

	/**
	 * 조립 모수 — 호출부가 "표시냐 판정이냐"를 매번 명시한다(2026-08-13 완결 배치 서빙 리뷰 결정).
	 * 기본값을 두지 않는 이유: 정산 게이트는 표시 계약이지 존재 계약이 아니라서, 조용히 상속되면
	 * 존재·집계 판정이 수집 중인 게시물을 "없다"고 답하게 된다.
	 */
	public enum BrandPostScope {
		/** 보강 정산분만(enriched_at IS NOT NULL) — 목록·상세·counts 같은 <b>표시</b> 표면 전용. */
		ENRICHED_ONLY,
		/** 정산 전 포함 전량 — 존재 판정·중복 판정·지표 집계처럼 누락이 오답이 되는 곳. */
		ALL
	}

	/**
	 * 브랜드 풀(tagged ∪ direct) 조립 — {@code brand_tagged_post} 한 산지에서 통째로 읽는다(설계
	 * §결정 1).
	 *
	 * <p><b>공개 이유(2026-08-27 갱신)</b>: 더 이상 성과 대시보드가 아니다. 대시보드는 2단 조립
	 * (인덱스+하이드레이트)으로 전환돼 프로덕션에서 이 메서드를 타지 않고, 브랜드 화면의 진입점도
	 * {@link #indexForBrand} + {@link #hydrate}다. 지금 남은 사용처는 둘뿐이다:
	 * <ol>
	 *   <li><b>프로덕션</b> — {@code V2CampaignContentService}의 캠페인 태그 <b>존재 판정</b>
	 *       (userId 스코프·scope=ALL·클램프 off). 유저의 전 브랜드를 한 번에 훑어야 해서 브랜드
	 *       단위 2단 조립이 맞지 않는다.</li>
	 *   <li><b>테스트 전용</b> — {@code PerformanceContentAssembler.assembleSlim}(구 전량 조립)이
	 *       2단 조립의 <b>동치성 기준선</b>으로 남아 이 메서드를 경유한다. 기준선이 은퇴해도 위 1번이
	 *       남으므로 이 메서드 자체는 그때도 삭제 대상이 아니다.</li>
	 * </ol>
	 *
	 * <p>{@code withComments=false}면 댓글 배치 조회를 아예 돌리지 않는다(08-12 성과 대시보드 고정
	 * 지연 대응). 08-12 운영 덤프 실측에서 브랜드 1계정 조립 415ms 중 237ms(57%)가 댓글 윈도우 쿼리 +
	 * 18,860행 매핑이었다 — 목록·비교 표면은 댓글을 렌더하지 않으므로(FE 실측 확인) 이 비용은 매
	 * 요청 버려지고 있었다. 결과 게시물의 {@code recentComments}는 빈 목록,
	 * {@code commentsCollectedCount}는 0이 된다 — 스냅샷 유래 지표({@code commentsTotal}·
	 * {@code commentsHidden})는 영향이 없다.
	 *
	 * <p>{@code scope}는 편의 오버로드를 두지 않는다 — 호출부가 표시(ENRICHED_ONLY)와 판정(ALL)을
	 * 매번 골라야 한다({@link BrandPostScope} 주석 참조).
	 *
	 * @param userId 시딩(캠페인 연결) 판정 스코프(2026-08-18 캠페인 도출 개정) — 캠페인은 브랜드가
	 *               아니라 유저 단위 개념이라 {@code account}가 아니라 이 값으로 조회한다.
	 * @param capToCoverage true면 실수집 범위 클램프(수집 상한 v2 §7-1) — coveredUntil(KST 달력일·
	 *               경계일 포함)보다 깊은 tagged 행을 거른다(direct 등록 행은 상한 밖이라 면제, §7-3).
	 *               성과 대시보드 집계 전용이다. false는 전량 — 목록 표면(컷 밖 기수집분도 계속
	 *               서빙, v1 §3-3)과 존재·중복 판정 소비자("있는데 없다고 답하면 안 됨")가 쓴다.
	 *               scope처럼 편의 오버로드를 두지 않는다 — 호출부가 매번 의도를 밝힌다.
	 * @param viewerAccountType 조회 유저의 이 브랜드 연결 accountType(2026-08-19 경쟁사 판정 제거
	 *                          설계 §4) — competitor면 광고 표기 4필드를 노출하지 않는다.
	 *                          {@link #brandPost}에 그대로 넘긴다.
	 */
	public List<BrandPostResponse> assembleBrandPosts(long userId, BrandAccountRow account, boolean withComments,
			BrandPostScope scope, boolean capToCoverage, String viewerAccountType) {
		List<BrandTaggedPostRow> allPosts = brandReadRepository.findBrandPostsInWindow(account.id(), windowCutoff(),
				scope == BrandPostScope.ENRICHED_ONLY);
		// 커버리지 클램프(수집 상한 v2 §7-1, 2026-08-20 사용자 결정 — 성과 대시보드는 실수집 범위만
		// 집계한다). 기준은 coveredUntil의 KST 달력일·경계일 포함 — covered 버킷 판정과 같은 규칙이라
		// covered=true 버킷의 게시물이 집계에서 빠지는 불일치가 없다. direct 등록 행은 상한 밖(§7-3 —
		// 컷 밖이어도 계속 실수집되는 살아있는 추적 대상)이라 면제. 행 단계에서 걸러 컷 밖 수천 행의
		// 메타·스냅샷·댓글 배치 조회 비용도 함께 던다.
		LocalDate coveredOn = capToCoverage ? KstTimestamps.toKstDate(account.coveredUntil()) : null;
		if (coveredOn != null) {
			allPosts = allPosts.stream()
					.filter(p -> p.directRegisteredAt() != null
							|| !KstTimestamps.toKstDate(p.takenAt()).isBefore(coveredOn))
					.toList();
		}
		if (allPosts.isEmpty()) {
			return List.of();
		}

		// 노출 필터(등록자 전용 노출 요구사항, 08-19) — direct-only(tag_detected_at IS NULL)는 등록한
		// 유저에게만 보인다. 원장 조회는 direct 등록 행이 하나도 없으면 생략한다(불필요한 조회 방지 —
		// exposeAdDisclosure 토글과 같은 관용구).
		boolean hasDirectRegistration = allPosts.stream().anyMatch(p -> p.directRegisteredAt() != null);
		Set<String> ownedShortCodes = hasDirectRegistration ? directPostRepository.shortCodesByUser(userId)
				: Set.of();
		List<BrandTaggedPostRow> posts = filterVisibleToUser(allPosts, ownedShortCodes);
		if (posts.isEmpty()) {
			return List.of();
		}

		Set<String> codes = posts.stream().map(BrandTaggedPostRow::shortCode)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, BrandPostMetaRow> metaByCode = brandReadRepository.findPostMeta(codes).stream()
				.collect(Collectors.toMap(BrandPostMetaRow::shortCode, Function.identity(), (a, b) -> a));
		Map<String, List<BrandSnapshotRow>> snapshotsByCode = brandReadRepository.findSnapshots(codes).stream()
				.collect(Collectors.groupingBy(BrandSnapshotRow::shortCode));
		Map<String, List<BrandCommentRow>> commentsByCode = !withComments ? Map.of()
				: brandReadRepository.findComments(codes, COMMENT_LIMIT).stream()
						.collect(Collectors.groupingBy(BrandCommentRow::shortCode));
		Map<String, AuthorRow> authorsByPost = resolveAuthors(posts);
		Map<String, List<String>> campaignIdsByCode = campaignIdsByCode(account.id(), codes);
		// 토글이 꺼져 있으면 조회 자체를 생략한다(드라이런 중 불필요한 조회 방지).
		Set<String> seededUsernames = !exposeAdDisclosure ? Set.of() : resolveSeededUsernames(userId);

		return posts.stream()
				.map(p -> brandPost(account.id(), p, metaByCode.get(p.shortCode()), authorsByPost.get(p.shortCode()),
						snapshotsByCode.getOrDefault(p.shortCode(), List.of()),
						commentsByCode.getOrDefault(p.shortCode(), List.of()), account.lastSweptAt(),
						campaignIdsByCode.getOrDefault(p.shortCode(), List.of()), exposeAdDisclosure, seededUsernames,
						ownedShortCodes.contains(p.shortCode()), viewerAccountType))
				.toList();
	}

	/**
	 * 노출 필터(요구사항 §2, 08-19) — 해시태그로 감지된 게시물({@code tag_detected_at IS NOT NULL})은
	 * 전원 노출, 직접 등록 전용 게시물({@code tag_detected_at IS NULL && direct_registered_at IS NOT
	 * NULL})은 등록한 유저에게만 노출한다. 등록자 원장은 {@code app.brand_direct_posts}
	 * ({@link BrandDirectPostRepository#shortCodesByUser}) — 2026-08-18 direct 통합 이후 신규 등록·
	 * 이관 전 레거시 등록을 모두 아우르는 유저 귀속 원장이다. monitoring DB({@code brand_tagged_post})와
	 * SQL 조인하지 않고 이 자바 코드에서 조합한다(시스템 경계 — was는 monitoring·app 스키마를 조인하지 않는다).
	 */
	private static List<BrandTaggedPostRow> filterVisibleToUser(List<BrandTaggedPostRow> posts,
			Set<String> ownedShortCodes) {
		return posts.stream()
				.filter(p -> p.tagDetectedAt() != null || ownedShortCodes.contains(p.shortCode()))
				.toList();
	}

	/**
	 * campaignIds 배치 조회(설계 §결정 3) — shortcode당 다건일 수 있어(N:M) 그룹핑한다.
	 *
	 * <p>공개 이유: 성과 대시보드 인덱스(2026-08-27)가 같은 판정을 공유한다 — 판정 함수 이원화 금지.
	 */
	public Map<String, List<String>> campaignIdsByCode(long brandId, Set<String> codes) {
		Map<String, List<String>> byCode = new LinkedHashMap<>();
		for (BrandPostCampaignRepository.Link link : postCampaignRepository.findByBrandAndShortCodes(brandId, codes)) {
			byCode.computeIfAbsent(link.shortCode(), k -> new ArrayList<>()).add(String.valueOf(link.campaignId()));
		}
		return byCode;
	}

	/**
	 * 시딩(캠페인 연결) 계정 username 집합(2026-08-18 캠페인 도출 개정, 2026-08-18 direct 통합 이후
	 * 재조정 — 신설 시딩 계정 관리 표면 대신 기존 캠페인 관리 데이터에서 산출한다) — 유저 스코프
	 * 합집합(캠페인은 브랜드가 아니라 유저 단위다):
	 *
	 * <ol>
	 *   <li>캠페인 연결 계정 추적(mode=account, canceled 제외)의 핸들 — {@code input_value}는
	 *       등록 시 이미 소문자 정규화 저장이라({@code MonitoringInput.parseAccount}) 방어적으로만
	 *       재정규화한다.</li>
	 *   <li>{@code app.brand_post_campaigns} 유저 스코프 링크의 게시자(설계 §결정 3 — direct 통합 후
	 *       캠페인 연결 정본, tagged·direct 공통) — shortcode는
	 *       {@link BrandPostCampaignRepository#findShortCodesByUser}로 얻고, 게시자 username은
	 *       monitoring DB {@code brand_post_meta}에서 별도 조회한다(app·monitoring이 물리적으로
	 *       다른 DB라 SQL 조인 불가 — 조합은 여기 was 코드에서).</li>
	 *   <li>이관 전(migrated_at IS NULL) 레거시 direct 등록의 캠페인 연결(canceled 제외) — shortcode는
	 *       app 스키마 내부 조인({@link BrandDirectPostRepository#findCampaignLinkedShortCodes})으로
	 *       얻는다. 과도기 전용: 이관 전 매핑은 아직 brand_post_meta에 없어 대부분 조회가 빈 값을
	 *       반환하지만(그 게시물 자체가 브랜드 풀 밖 — 과도기 폴백 카드는 seededAuthor를 애초에 항상
	 *       false로 응답한다, {@link #legacyPendingPost}), 태그 발견으로 이미 브랜드 풀에 들어온
	 *       겹침 게시물이면 이 작성자의 다른 브랜드 풀 게시물에 seededAuthor를 붙일 수 있다. 이관(M2)이
	 *       진행되면 이 소스는 2번 소스로 자연 흡수돼 비어간다.</li>
	 * </ol>
	 */
	private Set<String> resolveSeededUsernames(long userId) {
		Set<String> usernames = new LinkedHashSet<>();
		for (String handle : monitoringItemRepository.findCampaignLinkedAccountHandles(userId)) {
			usernames.add(handle.toLowerCase(Locale.ROOT));
		}
		Set<String> campaignLinkedShortCodes = new LinkedHashSet<>(postCampaignRepository.findShortCodesByUser(userId));
		campaignLinkedShortCodes.addAll(directPostRepository.findCampaignLinkedShortCodes(userId));
		if (!campaignLinkedShortCodes.isEmpty()) {
			for (BrandPostMetaRow meta : brandReadRepository.findPostMeta(campaignLinkedShortCodes)) {
				if (meta.username() != null) {
					usernames.add(meta.username().toLowerCase(Locale.ROOT));
				}
			}
		}
		return usernames;
	}

	/**
	 * 이 유저가 직접 등록한 게시물 원장(노출 필터·source 파생 입력) — {@code app.brand_direct_posts}.
	 *
	 * <p>공개 이유: 성과 대시보드 인덱스(2026-08-27)가 같은 원장을 봐야 한다. 리포지토리를 그쪽에
	 * 직접 주입하는 대신 이 경유로 노출한다 — 원장의 해석(무엇이 "내 등록인가")은 브랜드 조립의
	 * 책임이고, 대시보드는 그 판정을 빌려 쓸 뿐이다.
	 *
	 * <p>호출 관용구도 그대로 승계한다: direct 등록 행이 하나도 없으면 <b>호출하지 않는다</b>
	 * (불필요한 조회 방지 — {@link #assembleBrandPosts}·{@link #indexForBrand}와 동형).
	 */
	public Set<String> directRegisteredShortCodes(long userId) {
		return directPostRepository.shortCodesByUser(userId);
	}

	/**
	 * 게시자 해석 입력 키 — 산지(풀 행·성과 대시보드 인덱스 행)가 달라도 해석에 필요한 값은 이 셋뿐이다.
	 */
	public record AuthorKey(String shortCode, String igUserId, String username) {
	}

	/** 풀 행용 어댑터 — 해석 로직은 {@link #resolveAuthorsByKeys}가 단일 산지다. */
	private Map<String, AuthorRow> resolveAuthors(List<BrandTaggedPostRow> posts) {
		return resolveAuthorsByKeys(posts.stream()
				.map(p -> new AuthorKey(p.shortCode(), p.authorIgUserId(), p.authorUsername())).toList());
	}

	/**
	 * 게시자 프로필 해석 — 기본은 ig_user_id, 못 찾은 건만 username으로 한 번 더 찾는다(2차 SQL은
	 * 전부 해석되면 아예 돌지 않는다). 열거 셰이프에 따라 author_ig_user_id가 비는 행이 있어
	 * 폴백 경로가 필요하다({@code findAuthorsByUsername}은 계정당 최신 1행만 준다).
	 *
	 * <p>공개 이유: 성과 대시보드 인덱스(2026-08-27)가 같은 판정을 공유한다 — 판정 함수 이원화 금지.
	 */
	public Map<String, AuthorRow> resolveAuthorsByKeys(List<AuthorKey> keys) {
		Set<String> igUserIds = keys.stream().map(AuthorKey::igUserId)
				.filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, AuthorRow> byIgUserId = brandReadRepository.findAuthors(igUserIds).stream()
				.collect(Collectors.toMap(AuthorRow::igUserId, Function.identity(), (a, b) -> a));

		Map<String, AuthorRow> byPost = new LinkedHashMap<>();
		Set<String> pendingUsernames = new LinkedHashSet<>();
		for (AuthorKey key : keys) {
			AuthorRow row = key.igUserId() == null ? null : byIgUserId.get(key.igUserId());
			if (row != null) {
				byPost.put(key.shortCode(), row);
			} else if (key.username() != null) {
				pendingUsernames.add(key.username());
			}
		}
		if (pendingUsernames.isEmpty()) {
			return byPost;
		}

		Map<String, AuthorRow> byUsername = brandReadRepository.findAuthorsByUsername(pendingUsernames).stream()
				.collect(Collectors.toMap(AuthorRow::username, Function.identity(), (a, b) -> a));
		for (AuthorKey key : keys) {
			if (!byPost.containsKey(key.shortCode()) && key.username() != null) {
				AuthorRow row = byUsername.get(key.username());
				if (row != null) {
					byPost.put(key.shortCode(), row);
				}
			}
		}
		return byPost;
	}

	/**
	 * 브랜드 풀 1건 조립(설계 §3-3 필드 규칙표) — 지표·표시값의 산지가 전부 다르다: 게시물 메타는
	 * brand_post_meta, 게시자는 author_profile(부재 시 열거 관측값 폴백), 갱신 시각은
	 * {@code GREATEST(계정 마지막 스윕, 행의 마지막 크롤)}이다.
	 *
	 * <ul>
	 *   <li>{@code source}는 조회자 관점의 파생값이다(등록자 전용 노출 요구사항, 08-19) —
	 *       direct-only({@code tag_detected_at IS NULL})는 항상 "direct"(필터를 통과했다면 이 유저가
	 *       등록자다), 겹침 행(둘 다 값이 있음)은 이 유저가 등록자면 "direct", 아니면 "tagged".
	 *       {@link #resolveSource} 참조.</li>
	 *   <li>{@code trackingStartedAt}은 {@code COALESCE(direct_registered_at, first_seen_at)} —
	 *       direct 등록은 등록 순간부터, tagged는 처음 발견된 순간부터 추적 시작으로 본다.</li>
	 *   <li>{@code createdAt}은 항상 {@code first_seen_at}이다 — "이 브랜드가 이 게시물을 처음 본 시각"은
	 *       등록 경로와 무관하게 보존된다(direct 승격이 발견 이력을 덮지 않는다).</li>
	 *   <li>{@code updatedAt}은 행 단위 {@code last_crawled_at}과 계정의 마지막 스윕 중 늦은 값이다 —
	 *       direct 등록 직후 카드가 "어젯밤 스윕"으로 보이지 않게 한다.</li>
	 * </ul>
	 *
	 * <p>{@code commentsCollectedCount}는 조회된 댓글 행 수다 — {@code brand_tagged_post.
	 * comments_collected_count} 컬럼을 쓰지 않는다. 그 컬럼은 "마지막 수집 시점의 열거 comment_count"
	 * 즉 재수집 게이트의 워터마크라(monitoring BrandCollectService), 저장된 댓글 수와 다른 값이다.
	 */
	static BrandPostResponse brandPost(long brandId, BrandTaggedPostRow post, BrandPostMetaRow meta,
			AuthorRow author, List<BrandSnapshotRow> snapshotRows, List<BrandCommentRow> commentRows,
			OffsetDateTime lastSweptAt, List<String> campaignIds, boolean exposeAdDisclosure,
			Set<String> seededUsernames, boolean registeredByUser, String viewerAccountType) {
		String contentType = contentTypeOf(meta == null ? null : meta.contentType());
		List<TrackingItemResponse.SnapshotResponse> snapshots =
				snapshotRows.stream().map(BrandPostAssembler::snapshotOf).toList();
		List<TrackingItemResponse.PostCommentResponse> comments = commentRows.stream()
				.map(BrandPostAssembler::commentOf).filter(Objects::nonNull).toList();
		// author 행이 있어도 username이 비어 있으면 열거 관측으로 폴백한다(동치 계약 — refOfPoolRow와 같은 폴백).
		String username = author != null && author.username() != null ? author.username() : post.authorUsername();
		String source = resolveSource(post, registeredByUser);
		OffsetDateTime trackingStarted = post.directRegisteredAt() != null ? post.directRegisteredAt()
				: post.firstSeenAt();
		OffsetDateTime updated = latestOf(lastSweptAt, post.lastCrawledAt());
		// 광고 표기 판정 4필드(스펙 §9) — brand_post_meta는 source(tagged/direct)와 무관하게 shortcode
		// 1행 공용이다(설계 §결정 1, brand_tagged_post 단일 산지 통합) — tagged·direct 모두 같은 meta에서
		// 읽는다. 겹침 게시물이 direct 셰이프로 고정되던 구 모델의 "tagged 값 승격" 병합 단계는 이제
		// 필요 없다 — 애초에 행이 하나뿐이라 승격할 별도 값이 없다.
		// 경쟁사 조회자 제외(2026-08-19 경쟁사 판정 제거 설계 §4) — 브랜드 자체의 판정 존재 여부와
		// 무관하게, 이 게시물을 보는 유저의 연결이 competitor면 무조건 노출하지 않는다(같은 브랜드를
		// own으로 보는 다른 유저에게는 그대로 노출된다 — 노출은 연결 단위 판정이라 seededAuthor 등
		// 다른 필드처럼 브랜드 단위로 고정하지 않는다).
		boolean exposeAd = exposeAdDisclosure && meta != null
				&& !BrandAccountType.COMPETITOR.equals(viewerAccountType);
		String adDisclosure = exposeAd ? meta.adVerdict() : null;
		List<String> adViolations = exposeAd ? parseViolations(post.shortCode(), meta.adViolationsJson()) : List.of();
		List<BrandPostResponse.AdEvidence> adEvidence =
				exposeAd ? parseEvidence(post.shortCode(), meta.adEvidenceJson()) : List.of();
		// meta != null 가드가 없는 비대칭은 의도된 설계다 — 시딩(캠페인 연결) 여부는 이 게시물의
		// 메타(brand_post_meta)와 무관하게 유저 스코프로 미리 산출된 집합(resolveSeededUsernames,
		// 2026-08-18 캠페인 도출 개정)이라, 메타가 없어도(예: 아직 보강 전) 작성자가 그 집합에
		// 있으면 seededAuthor는 true여야 한다.
		boolean seededAuthor = exposeAdDisclosure && username != null
				&& seededUsernames.contains(username.toLowerCase(Locale.ROOT));

		return new BrandPostResponse(
				post.shortCode(),
				String.valueOf(brandId),
				source,
				postUrl(contentType, post.shortCode()),
				post.shortCode(),
				contentType,
				KstTimestamps.toKstIso(post.takenAt()),
				meta == null ? null : meta.caption(),
				meta == null ? null : resolveImageUrl(meta.imageObjectPath(), meta.thumbnailUrl()),
				meta == null ? null : meta.videoUrl(),
				meta == null ? null : meta.videoDuration(),
				username == null ? null : PROFILE_URL_PREFIX + username + "/",
				username,
				author == null ? null : author.fullName(),
				author == null ? null : resolveImageUrl(author.imageObjectPath(), author.profilePicUrl()),
				author != null && Boolean.TRUE.equals(author.isVerified()),
				author == null ? null : author.followers(),
				BrandSponsorshipClassifier.classify(meta == null ? null : meta.isPaidPartnership(),
						meta == null ? null : meta.caption()),
				meta == null ? null : meta.isPaidPartnership(),
				// 야간 스윕이 삭제·비공개(404)를 관측한 행은 hidden(2026-08-25 설계) — FE 칩("삭제·비공개")의
				// 유일한 트리거. direct는 2단계 단건 수집이, tagged는 커버 열거 부재의 검증 콜이 마킹한다
				// (tagged 확장 설계 — 둘 다 unavailable_at 단일 표식).
				post.unavailableAt() != null ? HIDDEN : TRACKING,
				KstTimestamps.toKstIso(trackingStarted),
				// 종료 개념이 없다 — 취소가 유일한 종료 경로고, 취소되면 이 행 자체가 목록에서 빠진다.
				null,
				latestOf(snapshots),
				snapshots,
				commentsTotal(snapshots),
				commentsHidden(snapshots),
				comments.size(),
				comments,
				campaignIds,
				KstTimestamps.toKstIso(post.firstSeenAt()),
				KstTimestamps.toKstIso(updated),
				adDisclosure,
				adViolations,
				adEvidence,
				seededAuthor);
	}

	/**
	 * source 파생(등록자 전용 노출 요구사항, 08-19) — tagged-only는 항상 "tagged". direct-only
	 * ({@code tag_detected_at IS NULL})는 항상 "direct"다: {@link #filterVisibleToUser}를 통과한
	 * 시점에 이미 이 유저가 등록자임이 보장된다. 겹침 행(둘 다 값이 있음, 전원 노출 대상)만 조회자
	 * 관점으로 갈린다 — 등록자에겐 "direct", 그 외 유저에겐 "tagged"(요구사항 §3).
	 */
	private static String resolveSource(BrandTaggedPostRow post, boolean registeredByUser) {
		return resolveSource(post.tagDetectedAt(), post.directRegisteredAt(), registeredByUser);
	}

	/**
	 * 판정 코어 — 풀 행({@code BrandTaggedPostRow})과 인덱스 행이 같은 파생 규칙을 공유한다.
	 *
	 * <p>공개 이유: 성과 대시보드 인덱스(2026-08-27)가 같은 판정을 공유한다 — 판정 함수 이원화 금지.
	 */
	public static String resolveSource(OffsetDateTime tagDetectedAt, OffsetDateTime directRegisteredAt,
			boolean registeredByUser) {
		if (directRegisteredAt == null) {
			return SOURCE_TAGGED;
		}
		if (tagDetectedAt == null) {
			return SOURCE_DIRECT;
		}
		return registeredByUser ? SOURCE_DIRECT : SOURCE_TAGGED;
	}

	/**
	 * ad_violations jsonb 텍스트 → 코드 배열. null·빈 배열은 빈 목록. 손상된 JSON(파싱 실패)은 목록
	 * 조회 전체를 500으로 죽이지 않도록 이 필드만 중립값(빈 목록)으로 격리하고 경고 로그를 남긴다
	 * (품질 리뷰 반영, 08-18 — 판정 파이프라인 버그가 브랜드 화면 전체 장애로 번지면 안 된다).
	 */
	private static List<String> parseViolations(String shortCode, String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			JsonNode node = OM.readTree(json);
			List<String> out = new ArrayList<>();
			node.forEach(n -> out.add(n.asString()));
			return out;
		} catch (JacksonException e) {
			log.warn("ad_violations jsonb 파싱 실패 — 빈 목록으로 격리 shortCode={}, json={}", shortCode,
					truncate(json), e);
			return List.of();
		}
	}

	/**
	 * ad_evidence jsonb 텍스트 → 근거 문구 배열. null·빈 배열은 빈 목록. parseViolations와 같은 이유로
	 * 손상된 JSON은 이 필드만 중립값으로 격리한다.
	 */
	private static List<BrandPostResponse.AdEvidence> parseEvidence(String shortCode, String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			JsonNode node = OM.readTree(json);
			List<BrandPostResponse.AdEvidence> out = new ArrayList<>();
			node.forEach(n -> out.add(new BrandPostResponse.AdEvidence(
					n.path("phrase").asString(), n.path("category").asString(), n.path("offset").asInt())));
			return out;
		} catch (JacksonException e) {
			log.warn("ad_evidence jsonb 파싱 실패 — 빈 목록으로 격리 shortCode={}, json={}", shortCode,
					truncate(json), e);
			return List.of();
		}
	}

	/** 경고 로그에 원문 전체를 싣지 않도록 축약(캡션 유래 데이터가 섞여 있을 수 있어 방어적으로 자른다). */
	private static String truncate(String text) {
		return text.length() <= 200 ? text : text.substring(0, 200) + "...(생략)";
	}

	/**
	 * 브랜드 스냅샷 → 응답 스냅샷. FEED는 views·shares·reposts를 null로 강제하고 sharesHidden까지
	 * 접는다(피드는 공유 미지원 — "비공개"와 다른 상태, 레거시 toSnapshotResponse와 같은 규칙).
	 * content_type 불명은 피드로 접는다: REELS로 잘못 단정해 0·거짓 지표를 노출하는 쪽이 더 나쁘다.
	 */
	public static TrackingItemResponse.SnapshotResponse snapshotOf(BrandSnapshotRow row) {
		boolean isFeed = !CONTENT_TYPE_REELS.equalsIgnoreCase(row.contentType());
		return new TrackingItemResponse.SnapshotResponse(
				row.capturedOn().toString(),
				isFeed ? null : row.views(),
				row.likes(),
				row.likesHidden(),
				row.comments(),
				row.saves(),
				isFeed ? null : row.shares(),
				!isFeed && row.sharesHidden(),
				isFeed ? null : row.reposts());
	}

	/** 필드 결손 댓글은 통째로 제외(레거시 PostComment 계약 동형). author는 마스킹 후에만 나간다. */
	static TrackingItemResponse.PostCommentResponse commentOf(BrandCommentRow row) {
		if (row.author() == null || row.body() == null || row.commentedAt() == null) {
			return null;
		}
		TrackingItemResponse.PostCommentResponse.Reply reply = row.ownerReplyText() == null ? null
				: new TrackingItemResponse.PostCommentResponse.Reply(row.ownerReplyText());
		return new TrackingItemResponse.PostCommentResponse(row.id(), AuthorMask.mask(row.author()), row.body(),
				row.likeCount(), KstTimestamps.toKstIso(row.commentedAt()), reply);
	}

	// ---------- 과도기 폴백(설계 §4-1) ----------

	/**
	 * TODO(contract): 이관 잡(M2)이 완주하면 이 메서드와 {@link BrandDirectPostRepository#findPendingByUser}·
	 * {@link TrackingItemAssembler} 의존을 함께 제거한다(설계 §4-1, C1).
	 *
	 * <p>{@code migrated_at IS NULL}인 매핑(구 direct 등록 — 이관 잡이 아직 못 옮긴 것)만 레거시
	 * 파이프라인으로 조립해 반환한다. 롤링 배포 창·이관 진행 중에도 카드가 사라지지 않게 하는 장치다.
	 */
	private List<BrandPostResponse> assembleLegacyPending(long userId, long brandId) {
		List<BrandDirectPostRepository.Row> rows = directPostRepository.findPendingByUser(userId).stream()
				.filter(r -> r.brandId() == brandId)
				.toList();
		if (rows.isEmpty()) {
			return List.of();
		}

		// 레거시 목록은 유저 전량을 한 번에 조립한다 — 매핑 건수만큼 단건 조립을 반복하면 N+1이다.
		TrackingItemAssembler.AssembledList assembled = trackingItemAssembler.assembleList(userId);
		Map<String, TrackingItemResponse> itemsById = assembled.items().stream()
				.collect(Collectors.toMap(TrackingItemResponse::id, Function.identity(), (a, b) -> a));

		List<BrandPostResponse> legacy = new ArrayList<>(rows.size());
		for (BrandDirectPostRepository.Row row : rows) {
			TrackingItemResponse item = itemsById.get(String.valueOf(row.monitoringItemId()));
			if (item == null) {
				// 매핑이 가리키는 레거시 행이 사라진 경우(수동 정리 등) — 목록을 죽이지 않고 건너뛴다.
				log.warn("이관 전 직접 등록 매핑의 레거시 아이템 부재 — 건너뜀 userId={}, shortCode={}, itemId={}",
						userId, row.shortCode(), row.monitoringItemId());
				continue;
			}
			legacy.add(legacyPendingPost(brandId, row.shortCode(), item, assembled.lastCollectedAt()));
		}
		return legacy;
	}

	/**
	 * 과도기 폴백 전용 변환 — 레거시 TrackingItem을 BrandPost 셰이프로 옮긴다. TODO(contract) —
	 * {@link #assembleLegacyPending}과 함께 C 단계에서 제거한다.
	 *
	 * <p>shortcode는 반드시 매핑 행 값이다 — 레거시 응답엔 shortcode 필드가 없고(post.url만 있다)
	 * URL 파싱은 등록 원문 형태에 좌우된다.
	 *
	 * @param legacyLastCollectedAt 레거시 마지막 성공 스윕 시각 — 이관 전 매핑의 갱신 시각은 브랜드
	 *                              스윕이 아니라 이쪽이다(브랜드 스윕은 이 게시물을 모른다).
	 */
	static BrandPostResponse legacyPendingPost(long brandId, String shortCode, TrackingItemResponse item,
			OffsetDateTime legacyLastCollectedAt) {
		TrackingItemResponse.TrackedPostResponse post = item.post();
		List<TrackingItemResponse.SnapshotResponse> snapshots = post == null ? List.of() : post.snapshots();
		List<TrackingItemResponse.PostCommentResponse> comments = post == null ? List.of() : post.recentComments();
		String caption = post == null ? null : post.caption();
		String username = item.handle() == null || item.handle().isEmpty() ? null : item.handle();

		return new BrandPostResponse(
				shortCode,
				String.valueOf(brandId),
				SOURCE_DIRECT,
				// 게시물 미확정(collecting)이면 매체를 모른다 — 피드 경로로 접는다(IG가 /p/를 리다이렉트).
				post != null ? post.url() : postUrl(FEED, shortCode),
				shortCode,
				post == null ? null : post.contentType(),
				post == null ? null : post.uploadedAt(),
				caption,
				post == null ? null : post.thumbnailUrl(),
				// 레거시 수집엔 영상 원본 URL·길이가 없다(브랜드 전용 필드) — 이관 잡이 통합 풀로 옮기면 채워진다.
				null,
				null,
				username == null ? null : PROFILE_URL_PREFIX + username + "/",
				username,
				item.displayName() == null || item.displayName().isEmpty() ? null : item.displayName(),
				item.profileImageUrl(),
				// 레거시 프로필엔 인증 배지 관측이 없다 — 거짓 배지를 띄우지 않기 위해 false 고정.
				false,
				item.followers(),
				// 레거시엔 is_paid_partnership 관측이 없어 캡션 키워드만으로 판정한다.
				BrandSponsorshipClassifier.classify(null, caption),
				null,
				item.status(),
				item.registeredAt(),
				// 레거시 응답에 종료 시각 필드가 없다 — 취소·만료 시점을 서버가 알 수 없어 null로 둔다.
				null,
				latestOf(snapshots),
				snapshots,
				commentsTotal(snapshots),
				commentsHidden(snapshots),
				comments.size(),
				comments,
				item.campaignId() == null ? List.of() : List.of(item.campaignId()),
				item.registeredAt(),
				KstTimestamps.toKstIso(legacyLastCollectedAt),
				// direct 산지는 광고 판정 정보가 없다(tagged 전용 — brand_post_meta 유래).
				null, List.of(), List.of(), false);
	}

	// 풀 우선 병합(mergeWithLegacyPending)은 indexForBrand의 legacyByCode 구성(풀 코드와 겹치면
	// 제외)으로 흡수됐다 — 병합 규칙 자체(브랜드 풀이 정본)는 그대로다.

	/**
	 * 업로드 날짜(KST) — takenAt은 산지에 따라 타임스탬프(브랜드 풀)와 날짜(과도기 폴백, 레거시
	 * uploaded_at이 date 컬럼)가 섞여 들어와서 앞 10자만 본다. 정렬·업로드 기간 필터의 공용 키다.
	 */
	static LocalDate uploadedOn(BrandPostResponse post) {
		String takenAt = post.takenAt();
		if (takenAt == null || takenAt.length() < 10) {
			return null;
		}
		try {
			return LocalDate.parse(takenAt.substring(0, 10));
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---------- 공용 ----------

	private static String contentTypeOf(String raw) {
		return CONTENT_TYPE_REELS.equalsIgnoreCase(raw) ? REELS : FEED;
	}

	private static String postUrl(String contentType, String shortCode) {
		return PROFILE_URL_PREFIX + (REELS.equals(contentType) ? "reel" : "p") + "/" + shortCode + "/";
	}

	private static TrackingItemResponse.SnapshotResponse latestOf(
			List<TrackingItemResponse.SnapshotResponse> snapshots) {
		// 스냅샷은 captured_on 오름차순으로 온다(BrandReadRepository.findSnapshots 계약) — 마지막이 최신.
		return snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
	}

	/** null-safe 최댓값(GREATEST) — 계정 마지막 스윕과 행 마지막 크롤 중 늦은 값(설계 §3-3 updatedAt). */
	private static OffsetDateTime latestOf(OffsetDateTime a, OffsetDateTime b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return a.isAfter(b) ? a : b;
	}

	private static Long commentsTotal(List<TrackingItemResponse.SnapshotResponse> snapshots) {
		TrackingItemResponse.SnapshotResponse latest = latestOf(snapshots);
		return latest == null ? null : latest.comments();
	}

	/** 댓글 숨김 = 스냅샷은 있는데 댓글 수가 비어 있는 상태. 스냅샷 자체가 없으면 "아직 모름"이다. */
	private static boolean commentsHidden(List<TrackingItemResponse.SnapshotResponse> snapshots) {
		TrackingItemResponse.SnapshotResponse latest = latestOf(snapshots);
		return latest != null && latest.comments() == null;
	}

	/** 저장 측이 걸러도 이미 박힌 값이 있을 수 있어 서빙에서 한 번 더 방어한다(레거시 sanitizeImageUrl 동형). */
	private static String sanitizeImageUrl(String url) {
		if (url == null) {
			return null;
		}
		String lower = url.toLowerCase(Locale.ROOT);
		return lower.startsWith("http://") || lower.startsWith("https://") ? url : null;
	}

	/**
	 * 이미지 URL 산지(레거시 {@code TrackingItemAssembler.resolveImageUrl} 동형) — image_object_path
	 * (monitoring이 자체 아카이브한 OCI 오브젝트)가 있으면 그걸 우선 서빙하고, 없으면 원본 CDN URL로
	 * 폴백한다(sanitizeImageUrl 가드는 폴백 경로에 그대로 유지). 원본은 인스타 서명 URL이라 며칠~2주면
	 * 만료된다 — 아카이브 사본이 서빙 정본이다. {@code /img/}는 was 엔드포인트가 아니라 celfit-front의
	 * Vercel rewrite({@code /img/:path*} 글롭)라서 프론트 변경이 필요 없다.
	 *
	 * <p>공개 이유: 성과 대시보드의 경량 ref({@code PerformanceContentAssembler.refOfPoolRow})가 카드를
	 * 조립하지 않고도 같은 아카이브 우선 규칙을 공유해야 한다 — 판정 함수를 이원화하면 ref와 카드의
	 * 프로필 이미지가 갈린다.
	 */
	public static String resolveImageUrl(String imageObjectPath, String originalUrl) {
		if (imageObjectPath != null) {
			return "/img/" + imageObjectPath;
		}
		return sanitizeImageUrl(originalUrl);
	}
}
