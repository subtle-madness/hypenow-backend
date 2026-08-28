package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.AuthorRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.v1.brandmonitoring.BrandHashtagPostAssembler;
import com.celfit.was.v1.brandmonitoring.BrandHashtagPostResponse;
import com.celfit.was.v1.brandmonitoring.BrandPostAssembler;
import com.celfit.was.v1.brandmonitoring.BrandPostAssembler.BrandPostIndex;
import com.celfit.was.v1.brandmonitoring.BrandPostAssembler.PostRef;
import com.celfit.was.v1.brandmonitoring.BrandPostResponse;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 툴 8종 실행기(설계 §4, 2026-08-28 search_posts·aggregate_posts 신설) - 전부 읽기 전용이고,
 * <b>brandId·shortCode 소유 검증이 이 클래스 안에서 강제된다</b>. LLM이 임의 id를 넘길 수 있다는
 * 전제로 짜여 있으며, 검증에 걸리면 예외가 아니라 failed 결과를 돌려 모델이 스스로 물러나게 한다.
 *
 * <p><b>2026-08-27 격리 계통 재배치(리뷰 C1/I2/I3/I4/I9)</b> — 브랜드 풀은 유저 간 공유라 원시
 * {@link BrandReadRepository} 직접 호출로는 FE가 강제하는 유저별 가시성 필터(direct-only 게시물은
 * 등록자 전용)·표시 창(collectionMonths) 검사·경쟁사 광고 판정 억제를 건너뛴다. 게시물 관련 툴
 * (list_posts·search_posts·aggregate_posts·get_post·get_comments·list_hashtag_posts)은 전부 FE와
 * 같은 조립 경로 — {@link BrandPostAssembler#indexForBrand}·{@link BrandPostAssembler#hydrate}·
 * {@link BrandHashtagPostAssembler#assembleForBrand} — 위에서 동작한다({@link
 * com.celfit.was.v1.brandmonitoring.V1BrandPostsController}가 정본 참조 구현). 이 경로가 이미
 * ENRICHED_ONLY·등록자 원장 필터·창 검사·경쟁사 억제를 전부 강제하므로 이 클래스가 다시 구현하지
 * 않는다 - 여기 남은 책임은 브랜드/게시물 소유 검증과 모델 응답용 페이로드 축약(건수 상한·캡션
 * 발췌)뿐이다.
 *
 * <p>{@link BrandReadRepository}는 brandId를 검증 없이 조회한다(그 클래스 javadoc 명시) - 소유
 * 스코프는 호출자 책임이고, 이 클래스가 그 호출자다. 반드시 {@link BrandLinkRepository}에서 얻은
 * brandId만 넘긴다.
 *
 * <p>건수 상한(게시물 30·댓글 50·시계열 14)은 모델 요청값과 무관하게 여기서 자른다(설계 §7) -
 * 토큰 폭발 방지가 목적이라 프롬프트 지시로는 보장할 수 없다. 댓글은 실질적으로
 * {@link BrandPostAssembler}의 서빙 상한(45건, 표시 표면과 동일)을 넘지 못한다 - FE가 보는 것
 * 이상을 어시스턴트가 볼 수는 없다.
 */
public class BrandAiToolbox {

	/** 게시물 목록 상한 - 30건이면 "최근 흐름"을 판단하기 충분하고 캡션 발췌 포함 토큰이 통제된다. */
	private static final int MAX_POSTS = 30;
	/** 댓글 상한(설계 §7) - 실제 반환 건수는 BrandPostAssembler 서빙 상한(45건)을 넘지 않는다. */
	private static final int MAX_COMMENTS = 50;
	private static final int DEFAULT_COMMENTS = 20;
	private static final int MAX_HASHTAG_POSTS = 30;
	/** 지표 시계열 상한 - 최근 14일이면 상승/정체 판단에 충분하다. */
	private static final int MAX_SNAPSHOTS = 14;
	private static final int DEFAULT_DAYS = 30;
	private static final int MAX_DAYS = 365;
	private static final int CAPTION_EXCERPT_LENGTH = 120;
	private static final int CAPTION_FULL_LENGTH = 1500;
	/** search_posts 상세 노출 상한 - totalMatches는 이 값과 무관하게 창 안 전체를 센다(설계 §요구). */
	private static final int MAX_SEARCH_MATCHES = 20;
	private static final int SEARCH_CAPTION_EXCERPT_LENGTH = 160;
	/** 댓글 본문 절단 길이(I6) - 인스타 댓글은 최대 2,200자라 45건 무절단 × 매 턴 전체 재전송이면
	 * O(k²)로 토큰이 터진다. 300자면 맥락 파악에는 충분하다. */
	private static final int COMMENT_BODY_LENGTH = 300;

	private static final String SORT_PERFORMANCE_DESC = "performance_desc";

	private final BrandLinkRepository linkRepository;
	private final BrandReadRepository brandReadRepository;
	private final BrandPostAssembler postAssembler;
	private final BrandHashtagPostAssembler hashtagPostAssembler;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public BrandAiToolbox(BrandLinkRepository linkRepository, BrandReadRepository brandReadRepository,
			BrandPostAssembler postAssembler, BrandHashtagPostAssembler hashtagPostAssembler,
			ObjectMapper objectMapper, Clock clock) {
		this.linkRepository = linkRepository;
		this.brandReadRepository = brandReadRepository;
		this.postAssembler = postAssembler;
		this.hashtagPostAssembler = hashtagPostAssembler;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	/**
	 * 세션 없는 호출 - 매 호출마다 새 {@link ToolSession}을 만들어 위임한다(캐시 미적용, 단발 호출·
	 * 기존 테스트 호환용). 실제 에이전트 루프는 {@link #execute(ToolSession, long, String, JsonNode)}로
	 * 요청 스코프 세션을 넘겨 인덱스 패스 중복을 없앤다(N2).
	 */
	public AiToolResult execute(long userId, String toolName, JsonNode args) {
		return execute(new ToolSession(), userId, toolName, args);
	}

	public AiToolResult execute(ToolSession session, long userId, String toolName, JsonNode args) {
		return switch (toolName) {
			case BrandAiToolSpecs.LIST_BRANDS -> listBrands(userId);
			case BrandAiToolSpecs.LIST_POSTS -> listPosts(session, userId, args);
			case BrandAiToolSpecs.SEARCH_POSTS -> searchPosts(session, userId, args);
			case BrandAiToolSpecs.AGGREGATE_POSTS -> aggregatePosts(session, userId, args);
			case BrandAiToolSpecs.GET_POST -> getPost(session, userId, args);
			case BrandAiToolSpecs.GET_COMMENTS -> getComments(session, userId, args);
			case BrandAiToolSpecs.LIST_HASHTAG_POSTS -> listHashtagPosts(userId, args);
			case BrandAiToolSpecs.GET_AUTHOR -> getAuthor(args);
			default -> error("알 수 없는 툴입니다: " + toolName);
		};
	}

	/**
	 * 에이전트 실행 1회 스코프의 캐시 컨테이너(N2, 2026-08-28) - {@link BrandPostAssembler#indexForBrand}는
	 * 유저 전 브랜드 풀을 다시 조립하는 무거운 호출인데, get_post→get_comments 연쇄나
	 * {@link #hydrateOwnedPost}의 유저 링크 순회에서 같은 (brandId, withViews) 조합을 턴당 최대 8회까지
	 * 반복 조회할 수 있다. 이 세션이 그 결과를 요청 스코프로만 재사용한다 - 툴박스 자체는 싱글턴 빈이라
	 * 인스턴스 필드에 캐시를 두면 유저 간 데이터가 섞이므로, 호출자({@link BrandAiAgent#run})가 매 요청
	 * 새로 만들어 매 execute 호출에 넘긴다.
	 */
	public static final class ToolSession {
		private final Map<IndexCacheKey, BrandPostIndex> indexCache = new HashMap<>();
	}

	/** 인덱스 캐시 키 - withViews 여부에 따라 latestViews 유무가 달라 브랜드마다 최대 두 변형이 있다. */
	private record IndexCacheKey(long brandId, boolean withViews) {
	}

	private BrandPostIndex indexFor(ToolSession session, long userId, BrandAccountRow account, boolean withViews) {
		return session.indexCache.computeIfAbsent(new IndexCacheKey(account.id(), withViews),
				key -> postAssembler.indexForBrand(userId, account, withViews));
	}

	private AiToolResult listBrands(long userId) {
		ArrayNode brands = objectMapper.createArrayNode();
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(userId)) {
			ObjectNode node = brands.addObject();
			node.put("brandId", link.brandId());
			node.put("username", link.username());
			node.put("accountType", link.accountType());
			node.put("collectionMonths", link.collectionMonths());
			brandReadRepository.findAccount(link.brandId()).ifPresent(account -> {
				node.put("followers", account.followers());
				node.put("mediaCount", account.mediaCount());
				node.put("fullName", account.fullName());
				node.put("biography", account.biography());
			});
		}
		return AiToolResult.ok(brands.toString(), brands.size(), List.of());
	}

	/**
	 * 목록(C1/I3/I9, N1 2026-08-28 재리뷰) - 컨트롤러 목록과 같은 2단 조립(indexForBrand + hydrate)을
	 * 탄다. 인덱스가 이미 노출 필터(direct-only 등록자 전용, {@link BrandPostAssembler#indexForBrand}
	 * 참조)를 강제해 원시 리포지토리 호출로는 새던 겹침 게시물이 여기선 애초에 후보에 없다.
	 * ENRICHED_ONLY도 indexForBrand 고정값이라 별도 스코프 인자가 필요 없다(I3).
	 *
	 * <p><b>링크 창과 모델의 days 필터는 서로 다른 판정이다</b>(N1 - {@link
	 * com.celfit.was.v1.brandmonitoring.V1BrandPostsController#list} 정본과 동형) - {@link
	 * #withinLinkWindow}는 direct 게시물을 면제하지만(유저가 명시 등록한 추적 대상이라 표시 창과
	 * 무관), 모델이 요청한 days는 "최근 N일"이라는 명시적 질문이라 direct라고 면제하면 2년 전 direct
	 * 등록 게시물이 "최근 7일" 답변에 섞이는 오답이 난다. 그래서 두 판정을 하나로 접지 않고 순서대로
	 * 적용한다: ① 링크 창(collectionMonths, direct 면제) → ② 모델 days(업로드일 기준, 면제 없음 -
	 * {@link com.celfit.was.v1.brandmonitoring.V1BrandPostsController#withinUploadWindow}와 같은 판정
	 * 함수를 이 값 하나만으로 호출한 것과 동형).
	 */
	private AiToolResult listPosts(ToolSession session, long userId, JsonNode args) {
		Optional<BrandLinkRow> linkOpt = ownedBrand(userId, args.path("brandId").asLong(0));
		if (linkOpt.isEmpty()) {
			return error("그 브랜드는 이 사용자의 모니터링 목록에 없거나 접근 권한이 없습니다. list_brands로 확인하세요.");
		}
		BrandLinkRow link = linkOpt.get();
		Optional<BrandAccountRow> accountOpt = brandReadRepository.findAccount(link.brandId());
		if (accountOpt.isEmpty()) {
			return error("그 브랜드의 계정 정보가 아직 수집되지 않았습니다.");
		}
		BrandAccountRow account = accountOpt.get();

		boolean performanceSort = SORT_PERFORMANCE_DESC.equals(args.path("sort").asString());
		BrandWindow window = resolveWindow(session, userId, link, account, args, performanceSort);
		BrandPostIndex index = window.index();

		Comparator<PostRef> order = performanceSort
				? Comparator.comparing(PostRef::latestViews, Comparator.nullsLast(Comparator.reverseOrder()))
				: Comparator.comparing(PostRef::uploadedOn, Comparator.nullsLast(Comparator.reverseOrder()));
		List<PostRef> page = window.inWindow().stream().sorted(order).limit(MAX_POSTS).toList();
		List<String> codes = page.stream().map(PostRef::shortcode).toList();

		// hydrate 반환 순서는 입력 codes 순서와 같다(BrandPostAssembler#hydrate 계약) - 위에서 이미
		// 정렬·페이지 슬라이스를 끝냈으니 재정렬이 필요 없다. 목록 표면 계약대로 댓글은 싣지 않는다.
		List<BrandPostResponse> posts = postAssembler.hydrate(userId, account, link.accountType(), index, codes,
				false);

		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("brandId", link.brandId());
		// since·totalInWindow는 반환된 posts 전원이 실제로 통과한 ② days 필터 기준이다(N1) - direct
		// 면제가 붙는 ①(링크 창)의 windowStart는 별개 상한이라 여기 싣지 않는다(이미 posts에 반영됨).
		payload.put("since", window.since().toString());
		payload.put("returned", posts.size());
		payload.put("totalInWindow", window.inWindow().size());
		ArrayNode postsNode = payload.putArray("posts");
		for (BrandPostResponse post : posts) {
			ObjectNode node = postsNode.addObject();
			node.put("shortCode", post.shortcode());
			node.put("takenAt", post.takenAt());
			node.put("isPaidPartnership", post.isPaidPartnership());
			node.put("caption", truncate(post.caption(), CAPTION_EXCERPT_LENGTH));
			node.put("authorUsername", post.authorUsername());
			TrackingItemResponse.SnapshotResponse latest = post.latestSnapshot();
			node.put("likes", latest == null ? null : latest.likes());
			node.put("comments", latest == null ? null : latest.comments());
			node.put("views", latest == null ? null : latest.views());
		}
		return AiToolResult.ok(payload.toString(), posts.size(), codes);
	}

	/**
	 * 링크 창(①, direct 면제) → 모델 days 필터(②, 면제 없음) 적용 공통 로직(N5, 2026-08-28 search_posts·
	 * aggregate_posts 신설) - list_posts의 {@link #listPosts} 본문에서 뽑아냈다. 세 툴 모두 이 두 판정을
	 * 같은 순서로 공유해야 한다(N1 주석 참조) - 따로 구현하면 창 정의가 갈릴 위험이 있다.
	 */
	private record BrandWindow(BrandPostIndex index, List<PostRef> inWindow, LocalDate since) {
	}

	private BrandWindow resolveWindow(ToolSession session, long userId, BrandLinkRow link, BrandAccountRow account,
			JsonNode args, boolean withViews) {
		BrandPostIndex index = indexFor(session, userId, account, withViews);

		LocalDate today = LocalDate.ofInstant(clock.instant(), KstTimestamps.KST);
		LocalDate windowStart = today.minusMonths(link.collectionMonths());
		List<PostRef> linkWindowRefs = index.refs().stream().filter(r -> withinLinkWindow(r, windowStart)).toList();

		int days = Math.clamp(args.path("days").asInt(DEFAULT_DAYS), 1, MAX_DAYS);
		LocalDate since = today.minusDays(days);
		List<PostRef> inWindow = linkWindowRefs.stream().filter(r -> withinUploadWindow(r.uploadedOn(), since))
				.toList();
		return new BrandWindow(index, inWindow, since);
	}

	/**
	 * 캡션 검색(신설, 2026-08-28) - "제품명 몇 번 언급됐어?" 같은 질문을 list_posts의 30건 발췌 목록을
	 * 모델이 눈으로 세는 방식으로 오답(실측: 273건 중 85건 언급을 0건으로 답함)내지 않도록, 창 안
	 * 전체를 대상으로 정확한 총 매칭 건수를 낸다. 캡션 매칭 자체는 {@link
	 * BrandReadRepository#findCaptionMatches}(풀 게시물, SQL ILIKE)와 과도기 폴백 카드(이미
	 * 인메모리인 캡션을 자바에서 같은 규칙으로 비교) 두 갈래를 합친다 - 창 안 전체를 매번
	 * hydrate하면 08-27에 고친 것과 같은 급의 타임아웃이 재발한다(설계 배경, "행×컬럼 매핑이
	 * 지배 비용").
	 *
	 * <p>상세(캡션 발췌·게시자·최신 지표)는 매칭 상위 {@value #MAX_SEARCH_MATCHES}건만 hydrate한다 -
	 * hydrate 비용은 입력 코드 수에 비례하므로 이 상한이 곧 실질 상한이다. totalMatches는 이 상한과
	 * 무관하게 창 안 전체 매칭 수 그대로다(설계 §요구 - "정확한 총 매칭 건수(상한 없음)"가 계약).
	 */
	private AiToolResult searchPosts(ToolSession session, long userId, JsonNode args) {
		String normalizedQuery = args.path("query").asString("").replace(" ", "");
		if (normalizedQuery.isEmpty()) {
			return error("query가 필요합니다.");
		}
		Optional<BrandLinkRow> linkOpt = ownedBrand(userId, args.path("brandId").asLong(0));
		if (linkOpt.isEmpty()) {
			return error("그 브랜드는 이 사용자의 모니터링 목록에 없거나 접근 권한이 없습니다. list_brands로 확인하세요.");
		}
		BrandLinkRow link = linkOpt.get();
		Optional<BrandAccountRow> accountOpt = brandReadRepository.findAccount(link.brandId());
		if (accountOpt.isEmpty()) {
			return error("그 브랜드의 계정 정보가 아직 수집되지 않았습니다.");
		}
		BrandAccountRow account = accountOpt.get();
		BrandWindow window = resolveWindow(session, userId, link, account, args, false);

		List<PostRef> poolRefs = new ArrayList<>();
		List<PostRef> legacyRefs = new ArrayList<>();
		for (PostRef ref : window.inWindow()) {
			(window.index().poolCodes().contains(ref.shortcode()) ? poolRefs : legacyRefs).add(ref);
		}
		Set<String> poolCodes = poolRefs.stream().map(PostRef::shortcode)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Set<String> matchedPoolCodes = brandReadRepository.findCaptionMatches(poolCodes, normalizedQuery);

		String lowerQuery = normalizedQuery.toLowerCase(Locale.ROOT);
		List<PostRef> matchedRefs = new ArrayList<>();
		for (PostRef ref : poolRefs) {
			if (matchedPoolCodes.contains(ref.shortcode())) {
				matchedRefs.add(ref);
			}
		}
		for (PostRef ref : legacyRefs) {
			BrandPostResponse legacy = window.index().legacyByCode().get(ref.shortcode());
			String caption = legacy == null ? null : legacy.caption();
			if (caption != null && caption.replace(" ", "").toLowerCase(Locale.ROOT).contains(lowerQuery)) {
				matchedRefs.add(ref);
			}
		}

		List<String> topCodes = matchedRefs.stream()
				.sorted(Comparator.comparing(PostRef::uploadedOn, Comparator.nullsLast(Comparator.reverseOrder())))
				.limit(MAX_SEARCH_MATCHES)
				.map(PostRef::shortcode)
				.toList();
		List<BrandPostResponse> hydrated = postAssembler.hydrate(userId, account, link.accountType(), window.index(),
				topCodes, false);
		Map<String, BrandPostResponse> hydratedByCode = hydrated.stream()
				.collect(Collectors.toMap(BrandPostResponse::shortcode, Function.identity(), (a, b) -> a));

		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("brandId", link.brandId());
		payload.put("since", window.since().toString());
		payload.put("totalMatches", matchedRefs.size());
		payload.put("totalInWindow", window.inWindow().size());
		ArrayNode matchesNode = payload.putArray("matches");
		List<String> codesOut = new ArrayList<>();
		for (String code : topCodes) {
			BrandPostResponse post = hydratedByCode.get(code);
			if (post == null) {
				continue;
			}
			codesOut.add(code);
			ObjectNode node = matchesNode.addObject();
			node.put("shortCode", post.shortcode());
			node.put("takenAt", post.takenAt());
			node.put("authorUsername", post.authorUsername());
			node.put("caption", truncate(post.caption(), SEARCH_CAPTION_EXCERPT_LENGTH));
			TrackingItemResponse.SnapshotResponse latest = post.latestSnapshot();
			node.put("likes", latest == null ? null : latest.likes());
			node.put("views", latest == null ? null : latest.views());
		}
		// rowCount는 다른 툴과 달리 "돌려준" 상위 건수가 아니라 총 매칭 건수다 - 이 툴의 핵심 계약이
		// 상한 없는 정확한 카운트라, 로그(app.ai_chat_logs.tool_calls[].rows)에도 진짜 수를 남긴다.
		return AiToolResult.ok(payload.toString(), matchedRefs.size(), codesOut);
	}

	/**
	 * 집계(신설, 2026-08-28) - 게시물 수·합계·평균 질문을 SQL 집계로 낸다(list_posts 30건 표본으로
	 * 어림잡지 않는다). 지표는 {@link BrandReadRepository#findLatestMetricsByShortCodes}로 인덱스가
	 * 이미 좁혀 놓은 풀 shortcode만 배치 조회한다 - search_posts와 같은 이유로 창 안 전체를
	 * hydrate하지 않는다. 과도기 폴백 카드는 소량이라 인메모리 값을 그대로 쓴다.
	 *
	 * <p>조회수는 릴스만 분모·분자에 들어간다(피드는 항상 null) - payload의 viewsNote가 그 규칙을
	 * 모델에게도 명시한다. 좋아요·댓글은 "수집된 것 기준"이라 스냅샷이 아예 없는(아직 미수집) 게시물은
	 * 표본에서 빠지고, 그 표본 수를 각각 *SampleCount로 함께 싣는다.
	 */
	private AiToolResult aggregatePosts(ToolSession session, long userId, JsonNode args) {
		Optional<BrandLinkRow> linkOpt = ownedBrand(userId, args.path("brandId").asLong(0));
		if (linkOpt.isEmpty()) {
			return error("그 브랜드는 이 사용자의 모니터링 목록에 없거나 접근 권한이 없습니다. list_brands로 확인하세요.");
		}
		BrandLinkRow link = linkOpt.get();
		Optional<BrandAccountRow> accountOpt = brandReadRepository.findAccount(link.brandId());
		if (accountOpt.isEmpty()) {
			return error("그 브랜드의 계정 정보가 아직 수집되지 않았습니다.");
		}
		BrandAccountRow account = accountOpt.get();
		BrandWindow window = resolveWindow(session, userId, link, account, args, false);

		Set<String> poolCodes = window.inWindow().stream().map(PostRef::shortcode)
				.filter(window.index().poolCodes()::contains)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, BrandReadRepository.LatestMetricsRow> metricsByCode = brandReadRepository
				.findLatestMetricsByShortCodes(poolCodes).stream()
				.collect(Collectors.toMap(BrandReadRepository.LatestMetricsRow::shortCode, Function.identity(),
						(a, b) -> a));

		long reelsCount = 0;
		long feedCount = 0;
		long totalViews = 0;
		long viewsSampleCount = 0;
		long totalLikes = 0;
		long likesSampleCount = 0;
		long totalComments = 0;
		long commentsSampleCount = 0;
		String topShortCode = null;
		Long topViews = null;

		for (PostRef ref : window.inWindow()) {
			String code = ref.shortcode();
			String contentType;
			Long views;
			Long likes;
			Long comments;
			if (window.index().poolCodes().contains(code)) {
				BrandReadRepository.LatestMetricsRow row = metricsByCode.get(code);
				contentType = row == null ? null : row.contentType();
				views = row == null ? null : row.views();
				likes = row == null ? null : row.likes();
				comments = row == null ? null : row.comments();
			} else {
				BrandPostResponse legacy = window.index().legacyByCode().get(code);
				TrackingItemResponse.SnapshotResponse latest = legacy == null ? null : legacy.latestSnapshot();
				contentType = legacy == null ? null : legacy.contentType();
				views = latest == null ? null : latest.views();
				likes = latest == null ? null : latest.likes();
				comments = latest == null ? null : latest.comments();
			}

			boolean isReels = BrandPostAssembler.CONTENT_TYPE_REELS.equalsIgnoreCase(contentType);
			if (isReels) {
				reelsCount++;
			} else {
				feedCount++;
			}
			if (isReels && views != null) {
				totalViews += views;
				viewsSampleCount++;
				if (topViews == null || views > topViews) {
					topViews = views;
					topShortCode = code;
				}
			}
			if (likes != null) {
				totalLikes += likes;
				likesSampleCount++;
			}
			if (comments != null) {
				totalComments += comments;
				commentsSampleCount++;
			}
		}

		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("brandId", link.brandId());
		payload.put("since", window.since().toString());
		payload.put("postCount", window.inWindow().size());
		payload.put("reelsCount", reelsCount);
		payload.put("feedCount", feedCount);
		payload.put("viewsNote", "피드 게시물은 조회수가 항상 null이라 조회수 집계·평균은 릴스만 대상입니다.");
		payload.put("totalViews", totalViews);
		payload.put("avgViews", viewsSampleCount == 0 ? null : (double) totalViews / viewsSampleCount);
		payload.put("viewsSampleCount", viewsSampleCount);
		payload.put("totalLikes", totalLikes);
		payload.put("avgLikes", likesSampleCount == 0 ? null : (double) totalLikes / likesSampleCount);
		payload.put("likesSampleCount", likesSampleCount);
		payload.put("totalComments", totalComments);
		payload.put("avgComments", commentsSampleCount == 0 ? null : (double) totalComments / commentsSampleCount);
		payload.put("commentsSampleCount", commentsSampleCount);
		List<String> codes;
		if (topShortCode != null) {
			ObjectNode topPost = payload.putObject("topPost");
			topPost.put("shortCode", topShortCode);
			topPost.put("views", topViews);
			codes = List.of(topShortCode);
		} else {
			codes = List.of();
		}
		return AiToolResult.ok(payload.toString(), window.inWindow().size(), codes);
	}

	/**
	 * 상세(C1/I4) - {@link #hydrateOwnedPost}가 컨트롤러 {@code get()}과 같은 관용구(유저 링크 순회 →
	 * indexForBrand → 창 검사 → hydrate)로 소유·창을 검증한다. 광고 표기 노출(토글+경쟁사 억제)은
	 * {@link BrandPostAssembler#brandPost} 게이트를 그대로 물려받는다(I4) - 여기선 값이 있으면 싣기만
	 * 한다. <b>상세 접근은 링크 창 판정만 탄다(N1)</b> - list_posts의 days 필터와 달리 get_post는 FE
	 * 상세 화면과 동일하게 표시 창(collectionMonths) 밖이 아니면 열린다.
	 *
	 * <p>댓글은 여기서 싣지 않으므로 hydrate는 {@code withComments=false}로 부른다(N2) - 댓글 배치
	 * 조회는 get_comments 전용이라, get_post 단독 호출에서 불필요한 댓글 조회를 없앤다.
	 */
	private AiToolResult getPost(ToolSession session, long userId, JsonNode args) {
		String shortCode = args.path("shortCode").asString();
		Optional<BrandPostResponse> found = hydrateOwnedPost(session, userId, shortCode, false);
		if (found.isEmpty()) {
			return error("그 게시물은 이 사용자의 브랜드 게시물 목록에 없거나 접근 권한이 없습니다. list_posts로 확인하세요.");
		}
		BrandPostResponse post = found.get();

		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("shortCode", post.shortcode());
		payload.put("authorUsername", post.authorUsername());
		payload.put("contentType", post.contentType());
		payload.put("uploadedAt", post.takenAt());
		payload.put("caption", truncate(post.caption(), CAPTION_FULL_LENGTH));
		payload.put("isPaidPartnership", post.isPaidPartnership());
		if (post.adDisclosure() != null) {
			payload.put("adDisclosure", post.adDisclosure());
		}
		ArrayNode metrics = payload.putArray("dailyMetrics");
		List<TrackingItemResponse.SnapshotResponse> snapshots = post.snapshots();
		List<TrackingItemResponse.SnapshotResponse> tail = snapshots.size() <= MAX_SNAPSHOTS
				? snapshots : snapshots.subList(snapshots.size() - MAX_SNAPSHOTS, snapshots.size());
		for (TrackingItemResponse.SnapshotResponse snapshot : tail) {
			ObjectNode node = metrics.addObject();
			node.put("capturedOn", snapshot.date());
			node.put("likes", snapshot.likes());
			node.put("comments", snapshot.comments());
			node.put("views", snapshot.views());
		}
		return AiToolResult.ok(payload.toString(), 1, List.of(shortCode));
	}

	/** 댓글(C1) - {@link #hydrateOwnedPost}로 같은 소유·창 검증을 거친 뒤 이미 하이드레이트된 댓글만 자른다. */
	private AiToolResult getComments(ToolSession session, long userId, JsonNode args) {
		String shortCode = args.path("shortCode").asString();
		Optional<BrandPostResponse> found = hydrateOwnedPost(session, userId, shortCode, true);
		if (found.isEmpty()) {
			return error("그 게시물은 이 사용자의 브랜드 게시물 목록에 없거나 접근 권한이 없습니다. list_posts로 확인하세요.");
		}
		int requested = args.path("limit").asInt(DEFAULT_COMMENTS);
		int limit = Math.clamp(requested, 1, MAX_COMMENTS);
		List<TrackingItemResponse.PostCommentResponse> rows = found.get().recentComments().stream()
				.limit(limit).toList();

		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("shortCode", shortCode);
		payload.put("limit", limit);
		payload.put("returned", rows.size());
		ArrayNode comments = payload.putArray("comments");
		for (TrackingItemResponse.PostCommentResponse row : rows) {
			ObjectNode node = comments.addObject();
			node.put("author", row.author());
			node.put("body", truncate(row.text(), COMMENT_BODY_LENGTH));
			node.put("likeCount", row.likes());
			node.put("commentedAt", row.createdAt());
			node.put("ownerReplyText", row.reply() == null ? null : row.reply().text());
		}
		return AiToolResult.ok(payload.toString(), rows.size(), List.of(shortCode));
	}

	/**
	 * 해시태그 발견 목록(I2) - {@link BrandHashtagPostAssembler#assembleForBrand}가 tagged 겹침 제외·
	 * 조회자 본인 태그 원장 교집합 필터·조회자 스코프 brandPostId를 전부 강제한다. 이 메서드는 모델
	 * 요청 창(days)만 얹어 자른다.
	 */
	private AiToolResult listHashtagPosts(long userId, JsonNode args) {
		Optional<BrandLinkRow> linkOpt = ownedBrand(userId, args.path("brandId").asLong(0));
		if (linkOpt.isEmpty()) {
			return error("그 브랜드는 이 사용자의 모니터링 목록에 없거나 접근 권한이 없습니다. list_brands로 확인하세요.");
		}
		BrandLinkRow link = linkOpt.get();
		List<BrandHashtagPostResponse> all = hashtagPostAssembler.assembleForBrand(userId, link.brandId());

		LocalDate cutoff = cutoffDateFor(link, args);
		List<BrandHashtagPostResponse> page = all.stream()
				.filter(row -> row.takenAt() != null && !OffsetDateTime.parse(row.takenAt()).toLocalDate().isBefore(cutoff))
				.sorted(Comparator.comparing(BrandHashtagPostResponse::takenAt,
						Comparator.nullsLast(Comparator.reverseOrder())))
				.limit(MAX_HASHTAG_POSTS)
				.toList();

		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("brandId", link.brandId());
		payload.put("returned", page.size());
		ArrayNode posts = payload.putArray("posts");
		List<String> codes = new ArrayList<>();
		for (BrandHashtagPostResponse row : page) {
			codes.add(row.shortcode());
			ObjectNode node = posts.addObject();
			node.put("shortCode", row.shortcode());
			node.put("matchedTag", row.matchedTag());
			node.put("authorUsername", row.authorUsername());
			node.put("takenAt", row.takenAt());
			node.put("caption", truncate(row.caption(), CAPTION_EXCERPT_LENGTH));
			node.put("likes", row.likes());
			node.put("comments", row.comments());
		}
		return AiToolResult.ok(payload.toString(), page.size(), codes);
	}

	/**
	 * 게시자 프로필은 브랜드 스코프로 좁히지 않는다 - author_profile은 공개 인스타그램 프로필
	 * (이름·팔로워·인증 배지)만 담고 사용자별 비공개 데이터가 없으며, 설계 §4의 소유 검증 대상도
	 * brandId·shortCode 둘로 명시돼 있다. 열람하려면 username을 이미 알아야 해서 열거 경로도 아니다.
	 */
	private AiToolResult getAuthor(JsonNode args) {
		String username = args.path("username").asString();
		if (username.isBlank()) {
			return error("username이 필요합니다.");
		}
		List<AuthorRow> rows = brandReadRepository.findAuthorsByUsername(List.of(username));
		if (rows.isEmpty()) {
			return error("그 계정의 프로필이 수집되지 않았습니다: " + username);
		}
		AuthorRow row = rows.get(0);
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("username", row.username());
		payload.put("fullName", row.fullName());
		payload.put("followers", row.followers());
		payload.put("isVerified", row.isVerified());
		return AiToolResult.ok(payload.toString(), 1, List.of());
	}

	/** 유저의 활성 링크에 있는 brandId만 통과시킨다 - 여기가 브랜드 소유 검증 지점이다. */
	private Optional<BrandLinkRow> ownedBrand(long userId, long brandId) {
		if (brandId <= 0) {
			return Optional.empty();
		}
		return linkRepository.findActiveByUserAndBrand(userId, brandId);
	}

	/**
	 * shortCode가 이 유저에게 보이는 어느 브랜드의 풀에 속하는지(C1) - {@link
	 * com.celfit.was.v1.brandmonitoring.V1BrandPostsController#get} 정본 관용구와 동형이다: 유저의
	 * 활성 링크를 순회하며 indexForBrand로 노출 필터를 태우고, 그 브랜드의 표시 창(collectionMonths)
	 * 안에 있는지까지 검사한 뒤에만 hydrate한다. 속하지 않거나 창 밖이면 empty - 컨트롤러의 404와
	 * 같은 취급이다. 브랜드 수는 유저당 최대 9개(own 6 + competitor 3)라 순회 비용이 상한선 안에 있다.
	 */
	private Optional<BrandPostResponse> hydrateOwnedPost(ToolSession session, long userId, String shortCode,
			boolean withComments) {
		if (shortCode == null || shortCode.isBlank()) {
			return Optional.empty();
		}
		LocalDate today = LocalDate.ofInstant(clock.instant(), KstTimestamps.KST);
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(userId)) {
			Optional<BrandAccountRow> accountOpt = brandReadRepository.findAccount(link.brandId());
			if (accountOpt.isEmpty()) {
				continue;
			}
			LocalDate windowStart = today.minusMonths(link.collectionMonths());
			BrandPostIndex index = indexFor(session, userId, accountOpt.get(), false);
			boolean present = index.refs().stream()
					.anyMatch(r -> r.shortcode().equals(shortCode) && withinLinkWindow(r, windowStart));
			if (!present) {
				continue;
			}
			List<BrandPostResponse> found = postAssembler.hydrate(userId, accountOpt.get(), link.accountType(),
					index, List.of(shortCode), withComments);
			if (!found.isEmpty()) {
				return Optional.of(found.get(0));
			}
		}
		return Optional.empty();
	}

	/**
	 * 모델이 요청한 days와 유저의 표시 기간(collectionMonths) 중 짧은 쪽을 KST 달력일 컷으로 - 창 밖
	 * 데이터는 보이면 안 된다(컨트롤러 {@code linkWindowStart}와 같은 산식, 날짜 단위로 통일).
	 */
	private LocalDate cutoffDateFor(BrandLinkRow link, JsonNode args) {
		LocalDate today = LocalDate.ofInstant(clock.instant(), KstTimestamps.KST);
		LocalDate windowStart = today.minusMonths(link.collectionMonths());
		int days = Math.clamp(args.path("days").asInt(DEFAULT_DAYS), 1, MAX_DAYS);
		LocalDate requested = today.minusDays(days);
		return requested.isAfter(windowStart) ? requested : windowStart;
	}

	/**
	 * 링크 창 판정(컨트롤러 {@code withinLinkWindow} 동형) - direct는 유저가 명시 등록한 추적 대상이라
	 * 창과 무관하게 통과한다. 나머지는 업로드일이 컷 이상이어야 한다(업로드일 미상은 제외). {@link
	 * BrandPostAssembler#SOURCE_DIRECT}를 직접 참조한다(N4) - 리터럴을 복제하면 값이 갈라져도 컴파일
	 * 에러 없이 드리프트한다.
	 */
	private static boolean withinLinkWindow(PostRef ref, LocalDate cutoff) {
		return BrandPostAssembler.SOURCE_DIRECT.equals(ref.source())
				|| (ref.uploadedOn() != null && !ref.uploadedOn().isBefore(cutoff));
	}

	/**
	 * 모델의 days 필터 판정(N1, 컨트롤러 {@code withinUploadWindow(uploadedOn, since, null)} 동형) -
	 * {@link #withinLinkWindow}와 달리 direct 면제가 없다: 모델이 "최근 N일"을 물었으면 direct
	 * 게시물도 그 기간 안에 올라온 것만 답해야 한다. 업로드일 미상은 판정 불가라 제외한다.
	 */
	private static boolean withinUploadWindow(LocalDate uploadedOn, LocalDate since) {
		return uploadedOn != null && !uploadedOn.isBefore(since);
	}

	/**
	 * 서로게이트 페어(이모지 등)를 쪼개지 않는 안전 절단(M4) - UTF-16 code unit 기준 substring은 이모지
	 * 중간을 잘라 깨진 문자를 만들 수 있어 code point 경계로 자른다.
	 */
	private static String truncate(String text, int max) {
		if (text == null) {
			return null;
		}
		if (text.codePointCount(0, text.length()) <= max) {
			return text;
		}
		int cut = text.offsetByCodePoints(0, max);
		return text.substring(0, cut) + "...";
	}

	private AiToolResult error(String message) {
		return AiToolResult.failure(objectMapper.createObjectNode().put("error", message).toString());
	}
}
