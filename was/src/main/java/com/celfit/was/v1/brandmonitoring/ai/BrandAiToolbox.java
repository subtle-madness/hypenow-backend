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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
	/** get_comments 배치 호출(2026-08-31, 스펙 §3-3) 상한 - 게시물 수·게시물당 건수·전체 건수 3중 상한. */
	private static final int MAX_COMMENT_POSTS = 5;
	private static final int PER_POST_DEFAULT_COMMENTS = 10;
	private static final int PER_POST_MAX_COMMENTS = 20;
	/** 배치 호출 전체 총 상한 - 기존 MAX_COMMENTS(50)와 같은 값이라 토큰 예산이 불변이다(스펙 §3-3). */
	private static final int TOTAL_BATCH_COMMENTS = 50;

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
			case BrandAiToolSpecs.LIST_BRANDS -> listBrands(session, userId);
			case BrandAiToolSpecs.LIST_POSTS -> listPosts(session, userId, args);
			case BrandAiToolSpecs.SEARCH_POSTS -> searchPosts(session, userId, args);
			case BrandAiToolSpecs.AGGREGATE_POSTS -> aggregatePosts(session, userId, args);
			case BrandAiToolSpecs.GET_POST -> getPost(session, userId, args);
			case BrandAiToolSpecs.GET_COMMENTS -> getComments(session, userId, args);
			case BrandAiToolSpecs.LIST_HASHTAG_POSTS -> listHashtagPosts(session, userId, args);
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
	 *
	 * <p>2026-08-30 FE scope 강제(T3) - 이 세션이 FE 화면 필터({@link AiScope})도 함께 들고 다닌다.
	 * 툴박스가 싱글턴이라 필터도 요청 스코프로만 살아야 하는 이유는 캐시와 같다.
	 *
	 * <p><b>F1(2026-08-30 리뷰) brandId 강제</b> - 컨트롤러가 accountIds[0]을 검증해 얻은 brandId를 이
	 * 세션이 함께 들고 다닌다. list_posts 등 brandId를 인자로 받는 툴은 모델이 이 유저가 소유한 <i>다른</i>
	 * brandId를 넣어도(예: list_brands로 얻은 경쟁사 계정 id) 여기 담긴 값과 다르면 소유 여부와 무관하게
	 * 막는다 - 소유 검증만으로는 "이 대화가 스코프된 계정"을 벗어난 조회를 막지 못한다(리뷰 지적). brandId가
	 * null이면 무제한(기존 2-인자 생성자 호환 - 단발 테스트·컨트롤러 미배선 경로 전용, 실제 요청 경로는
	 * 항상 값이 채워진다).
	 */
	public static final class ToolSession {
		private final Map<IndexCacheKey, BrandPostIndex> indexCache = new HashMap<>();
		private final AiScope scope;
		private final Long brandId;

		/** scope·brandId 없는 세션(기존 관용구 유지) - 무필터·무제한과 동일하다. */
		public ToolSession() {
			this(null, null);
		}

		/** scope만 있는 세션(기존 2-인자 관용구 유지, F1 이전 호출부·단발 테스트 호환) - brandId 제한 없음. */
		public ToolSession(AiScope scope) {
			this(scope, null);
		}

		public ToolSession(AiScope scope, Long brandId) {
			this.scope = scope;
			this.brandId = brandId;
		}

		AiScope scope() {
			return scope;
		}

		Long brandId() {
			return brandId;
		}

		/**
		 * 세션에 이미 캐시된 인덱스 전체에서 shortcode로 {@link PostRef}를 찾는다(references 라벨 조립
		 * 전용, FE 변경요청서 §7) - list_hashtag_posts 산지 게시물처럼 이 세션에서 인덱스를 한 번도
		 * 거치지 않은 shortcode는 못 찾는다(empty). 참조는 응답당 최대 10건이라 O(n) 스캔 비용이 작다.
		 */
		public Optional<PostRef> findCachedRef(String shortcode) {
			for (BrandPostIndex index : indexCache.values()) {
				for (PostRef ref : index.refs()) {
					if (ref.shortcode().equals(shortcode)) {
						return Optional.of(ref);
					}
				}
			}
			return Optional.empty();
		}
	}

	/** 인덱스 캐시 키 - withViews 여부에 따라 latestViews 유무가 달라 브랜드마다 최대 두 변형이 있다. */
	private record IndexCacheKey(long brandId, boolean withViews) {
	}

	private BrandPostIndex indexFor(ToolSession session, long userId, BrandAccountRow account, boolean withViews) {
		return session.indexCache.computeIfAbsent(new IndexCacheKey(account.id(), withViews),
				key -> postAssembler.indexForBrand(userId, account, withViews));
	}

	/**
	 * 세션에 brandId가 고정돼 있으면(F1) 그 브랜드 1건만 돌려준다 - 대화 하나는 accountIds[0] 하나에
	 * 묶인다는 계약(설계 §5)의 자연스러운 귀결이다. 여러 브랜드를 오가며 비교하는 질문은 이번 계약
	 * 범위 밖이라(설계 §요구), list_brands가 다른 브랜드까지 보여주면 모델이 그 브랜드로 다른 툴을
	 * 불러도 되는 것처럼 오인할 여지를 준다. 세션에 brandId가 없으면(단발 테스트 등) 기존처럼 전체를
	 * 돌려준다.
	 */
	private AiToolResult listBrands(ToolSession session, long userId) {
		ArrayNode brands = objectMapper.createArrayNode();
		Long sessionBrandId = session.brandId();
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(userId)) {
			if (sessionBrandId != null && link.brandId() != sessionBrandId) {
				continue;
			}
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
		long requestedBrandId = args.path("brandId").asLong(0);
		Optional<AiToolResult> mismatch = scopeMismatch(session, requestedBrandId);
		if (mismatch.isPresent()) {
			return mismatch.get();
		}
		Optional<BrandLinkRow> linkOpt = ownedBrand(userId, requestedBrandId);
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
		// since·totalInWindow는 반환된 posts 전원이 실제로 통과한 판정 기준이다 - days가 지정되면 ②
		// 필터 기준(N1, direct 면제 없음), 미지정이면 ①(링크 창, direct 면제) 시작일이다. window
		// 필드가 어느 쪽인지 모델에게 명시한다(2026-08-28 기간 기본값 수정).
		payload.put("since", window.since().toString());
		payload.put("window", window.windowKind());
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

	/** payload의 "window" 필드 값(2026-08-28 기간 기본값 수정) - days 미지정 시 모수가 수집 기간
	 * 전체(링크 창)라는 것을 모델이 답변에 명시할 수 있도록 판정 기준을 이름으로 노출한다. */
	private static final String WINDOW_COLLECTION = "collection_window";
	private static final String WINDOW_DAYS_FILTER = "days_filter";

	/**
	 * 링크 창(①, direct 면제) → 모델 days 필터(②, 면제 없음, 지정 시에만) 적용 공통 로직(N5, 2026-08-28
	 * search_posts·aggregate_posts 신설 / 2026-08-28 기간 기본값 수정) - list_posts의 {@link #listPosts}
	 * 본문에서 뽑아냈다. 세 툴 모두 이 두 판정을 같은 순서로 공유해야 한다(N1 주석 참조) - 따로 구현하면
	 * 창 정의가 갈릴 위험이 있다.
	 *
	 * <p><b>days 생략 = "수집된 전체"</b>(2026-08-28) - 기간을 말하지 않은 질문의 자연스러운 의미는
	 * 30일이 아니라 "지금까지 모은 것 전부"다. days가 없거나(missing) 0 이하로 오면 ② 필터를 아예
	 * 적용하지 않고 ①(링크 창, direct 면제) 결과를 그대로 모수로 쓴다 - since는 이때 링크 창 시작일
	 * (windowStart)이 된다. days가 1 이상으로 명시되면 기존과 동일하게 ②를 추가 적용한다(면제 없음,
	 * 1~365 클램프).
	 */
	private record BrandWindow(BrandPostIndex index, List<PostRef> inWindow, LocalDate since, String windowKind) {
	}

	private BrandWindow resolveWindow(ToolSession session, long userId, BrandLinkRow link, BrandAccountRow account,
			JsonNode args, boolean withViews) {
		BrandPostIndex index = indexFor(session, userId, account, withViews);
		AiScope scope = session.scope();

		LocalDate today = LocalDate.ofInstant(clock.instant(), KstTimestamps.KST);
		LocalDate windowStart = today.minusMonths(link.collectionMonths());
		List<PostRef> linkWindowRefs = index.refs().stream().filter(r -> withinLinkWindow(r, windowStart)).toList();

		LocalDate since;
		String windowKind;
		List<PostRef> dateWindowRefs;
		int requestedDays = args.path("days").asInt(0);
		if (requestedDays <= 0) {
			// days 미지정(또는 0) - 링크 창(①)을 그대로 모수로 쓴다. ② 필터는 적용하지 않는다.
			since = laterOf(windowStart, scope == null ? null : scope.dateFrom());
			windowKind = WINDOW_COLLECTION;
			dateWindowRefs = linkWindowRefs;
		} else {
			int days = Math.clamp(requestedDays, 1, MAX_DAYS);
			LocalDate daysSince = today.minusDays(days);
			since = laterOf(daysSince, scope == null ? null : scope.dateFrom());
			windowKind = WINDOW_DAYS_FILTER;
			dateWindowRefs = linkWindowRefs.stream().filter(r -> withinUploadWindow(r.uploadedOn(), daysSince))
					.toList();
		}

		// FE scope 강제(T3, 2026-08-30) - 모델이 뭐라 요청하든 scope 밖 데이터는 결과에 나타나지 않는다.
		// scope의 날짜는 위에서 이미 계산한 링크 창/days 창과 별개로 한 번 더 걸어 교집합을 만든다
		// (direct 게시물의 링크 창 면제와 달리 scope는 예외 없이 전부 적용된다).
		List<PostRef> scoped = dateWindowRefs.stream().filter(r -> matchesScope(r, scope)).toList();
		List<PostRef> inWindow = applyAuthorScope(scoped, scope);
		return new BrandWindow(index, inWindow, since, windowKind);
	}

	/** null을 "제약 없음"으로 보는 더 늦은(제약이 더 센) 날짜 - scope.dateFrom과 기존 since 후보 중 결합. */
	private static LocalDate laterOf(LocalDate a, LocalDate b) {
		if (b == null) {
			return a;
		}
		if (a == null) {
			return b;
		}
		return a.isAfter(b) ? a : b;
	}

	/**
	 * scope의 날짜(예외 없음)·미디어타입·소스·협찬 축 판정(T3) - 작성자 축(q·팔로워)은 배치 조회가
	 * 필요해 {@link #applyAuthorScope}·{@link #authorMatchesScope}로 뺐다. scope가 null이면 전부 통과
	 * (무필터, 기존 동작과 동일).
	 */
	private static boolean matchesScope(PostRef ref, AiScope scope) {
		if (scope == null) {
			return true;
		}
		if (scope.dateFrom() != null && (ref.uploadedOn() == null || ref.uploadedOn().isBefore(scope.dateFrom()))) {
			return false;
		}
		if (scope.dateTo() != null && (ref.uploadedOn() == null || ref.uploadedOn().isAfter(scope.dateTo()))) {
			return false;
		}
		if (scope.mediaType() != null && !scope.mediaType().equalsIgnoreCase(ref.contentType())) {
			return false;
		}
		if (scope.source() != null && !scope.source().equals(ref.source())) {
			return false;
		}
		if (scope.sponsorship() != null && !scope.sponsorship().equals(ref.sponsorship())) {
			return false;
		}
		return true;
	}

	/** {@link #findAuthorsByUsernameBatched} IN 절 배치 크기(F7, 2026-08-30 리뷰) - PostgreSQL 바인드
	 * 파라미터 상한(65,535)에는 한참 못 미치지만, 대량 브랜드(수집 기간이 길어 게시물·작성자 수가
	 * 큰 브랜드)의 단일 쿼리 비대화를 막는 상한을 미리 걸어 둔다. */
	private static final int AUTHOR_LOOKUP_BATCH_SIZE = 1_000;

	/**
	 * scope의 작성자 검색(q)·팔로워 범위 필터(T3) - author_username 배치 조회가 필요해 목록 단위로
	 * 뺐다. q·팔로워 조건이 전혀 없으면(가장 흔한 경우) 조회 자체를 생략한다. 작성자 정보가 없는
	 * (authorUsername null 또는 프로필 미수집) 게시물은 필터가 걸려 있으면 제외한다(설계 §요구).
	 */
	private List<PostRef> applyAuthorScope(List<PostRef> refs, AiScope scope) {
		if (scope == null || (scope.q() == null && scope.followerMin() == null && scope.followerMax() == null)) {
			return refs;
		}
		Set<String> usernames = refs.stream().map(PostRef::authorUsername).filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (usernames.isEmpty()) {
			return List.of();
		}
		Map<String, AuthorRow> byUsername = findAuthorsByUsernameBatched(usernames).stream()
				.collect(Collectors.toMap(AuthorRow::username, Function.identity(), (a, b) -> a));
		List<PostRef> out = new ArrayList<>();
		for (PostRef ref : refs) {
			if (ref.authorUsername() == null) {
				continue;
			}
			AuthorRow author = byUsername.get(ref.authorUsername());
			if (author != null && matchesAuthorScope(author, scope)) {
				out.add(ref);
			}
		}
		return out;
	}

	/**
	 * {@link BrandReadRepository#findAuthorsByUsername}을 {@value #AUTHOR_LOOKUP_BATCH_SIZE}건 단위로
	 * 쪼개 호출한다(F7, 2026-08-30 리뷰) - 그 리포지토리는 IN 절에 컬렉션을 그대로 바인딩해 요청 수만큼
	 * 배치 안에 담아 던진다(javadoc 명시). 창 안 게시물 수(usernames 상한)는 브랜드 수집 기간에 따라
	 * 커질 수 있어 여기서 상한 없이 넘기면 단일 쿼리가 비대해진다.
	 */
	private List<AuthorRow> findAuthorsByUsernameBatched(Set<String> usernames) {
		if (usernames.size() <= AUTHOR_LOOKUP_BATCH_SIZE) {
			return brandReadRepository.findAuthorsByUsername(usernames);
		}
		List<AuthorRow> out = new ArrayList<>();
		List<String> ordered = new ArrayList<>(usernames);
		for (int i = 0; i < ordered.size(); i += AUTHOR_LOOKUP_BATCH_SIZE) {
			List<String> batch = ordered.subList(i, Math.min(i + AUTHOR_LOOKUP_BATCH_SIZE, ordered.size()));
			out.addAll(brandReadRepository.findAuthorsByUsername(batch));
		}
		return out;
	}

	/**
	 * {@link #applyAuthorScope}의 해시태그 발견 게시물 버전(F6, 2026-08-30 리뷰) - list_hashtag_posts는
	 * {@link BrandHashtagPostResponse}를 다뤄 {@link PostRef}와 타입이 다르다. followers는 이 응답 자체에
	 * 없어(BrandHashtagPostResponse javadoc 참조 - 재수집 파이프라인이 없는 슬림 셰이프) q·팔로워 둘 다
	 * author_profile 배치 조회로만 판정한다 - {@link #matchesAuthorScope}(AuthorRow 기준)를 그대로 공유한다.
	 */
	private List<BrandHashtagPostResponse> applyHashtagAuthorScope(List<BrandHashtagPostResponse> rows,
			AiScope scope) {
		if (scope == null || (scope.q() == null && scope.followerMin() == null && scope.followerMax() == null)) {
			return rows;
		}
		Set<String> usernames = rows.stream().map(BrandHashtagPostResponse::authorUsername).filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (usernames.isEmpty()) {
			return List.of();
		}
		Map<String, AuthorRow> byUsername = findAuthorsByUsernameBatched(usernames).stream()
				.collect(Collectors.toMap(AuthorRow::username, Function.identity(), (a, b) -> a));
		List<BrandHashtagPostResponse> out = new ArrayList<>();
		for (BrandHashtagPostResponse row : rows) {
			if (row.authorUsername() == null) {
				continue;
			}
			AuthorRow author = byUsername.get(row.authorUsername());
			if (author != null && matchesAuthorScope(author, scope)) {
				out.add(row);
			}
		}
		return out;
	}

	/** {@link #applyAuthorScope}의 단건 버전 - get_post·get_comments(hydrateOwnedPost)는 shortCode 1건뿐이라 배치가 필요 없다. */
	private boolean authorMatchesScope(PostRef ref, AiScope scope) {
		if (scope == null || (scope.q() == null && scope.followerMin() == null && scope.followerMax() == null)) {
			return true;
		}
		if (ref.authorUsername() == null) {
			return false;
		}
		List<AuthorRow> rows = brandReadRepository.findAuthorsByUsername(List.of(ref.authorUsername()));
		return !rows.isEmpty() && matchesAuthorScope(rows.get(0), scope);
	}

	private static boolean matchesAuthorScope(AuthorRow author, AiScope scope) {
		if (scope.q() != null) {
			String lowerQ = scope.q().toLowerCase(Locale.ROOT);
			String username = author.username() == null ? "" : author.username().toLowerCase(Locale.ROOT);
			String fullName = author.fullName() == null ? "" : author.fullName().toLowerCase(Locale.ROOT);
			if (!username.contains(lowerQ) && !fullName.contains(lowerQ)) {
				return false;
			}
		}
		Long followers = author.followers();
		if (scope.followerMin() != null && (followers == null || followers < scope.followerMin())) {
			return false;
		}
		if (scope.followerMax() != null && (followers == null || followers > scope.followerMax())) {
			return false;
		}
		return true;
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
		long requestedBrandId = args.path("brandId").asLong(0);
		Optional<AiToolResult> mismatch = scopeMismatch(session, requestedBrandId);
		if (mismatch.isPresent()) {
			return mismatch.get();
		}
		Optional<BrandLinkRow> linkOpt = ownedBrand(userId, requestedBrandId);
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

		List<PostRef> matchedRefs = captionMatchedRefs(window, normalizedQuery);

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
		payload.put("window", window.windowKind());
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

	/** 창 안 refs 중 캡션이 normalizedQuery에 매칭되는 것만(2026-08-31 aggregate_posts keyword 필터 신설,
	 * 스펙 §3-1) - searchPosts와 aggregatePosts가 공유한다. 풀 게시물은 SQL ILIKE({@link
	 * BrandReadRepository#findCaptionMatches}), 과도기 레거시 카드는 인메모리 비교(기존 두 갈래 로직을
	 * searchPosts에서 그대로 옮긴 것 - 동작 무변화, 기존 search_posts 테스트가 회귀 가드다). */
	private List<PostRef> captionMatchedRefs(BrandWindow window, String normalizedQuery) {
		List<PostRef> poolRefs = new ArrayList<>();
		List<PostRef> legacyRefs = new ArrayList<>();
		for (PostRef ref : window.inWindow()) {
			(window.index().poolCodes().contains(ref.shortcode()) ? poolRefs : legacyRefs).add(ref);
		}
		Set<String> poolCodes = poolRefs.stream().map(PostRef::shortcode)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Set<String> matchedPoolCodes = brandReadRepository.findCaptionMatches(poolCodes, normalizedQuery);
		String lowerQuery = normalizedQuery.toLowerCase(Locale.ROOT);
		List<PostRef> matched = new ArrayList<>();
		for (PostRef ref : poolRefs) {
			if (matchedPoolCodes.contains(ref.shortcode())) {
				matched.add(ref);
			}
		}
		for (PostRef ref : legacyRefs) {
			BrandPostResponse legacy = window.index().legacyByCode().get(ref.shortcode());
			String caption = legacy == null ? null : legacy.caption();
			if (caption != null && caption.replace(" ", "").toLowerCase(Locale.ROOT).contains(lowerQuery)) {
				matched.add(ref);
			}
		}
		return matched;
	}

	/**
	 * 집계(신설, 2026-08-28 / 일반화 2026-08-31 - 스펙 §3-1·§3-2) - 게시물 수·합계·평균 질문을 SQL 집계로
	 * 낸다(list_posts 30건 표본으로 어림잡지 않는다). 지표는 {@link
	 * BrandReadRepository#findLatestMetricsByShortCodes}로 인덱스가 이미 좁혀 놓은 풀 shortcode만 배치
	 * 조회한다 - search_posts와 같은 이유로 창 안 전체를 hydrate하지 않는다. 과도기 폴백 카드는 소량이라
	 * 인메모리 값을 그대로 쓴다.
	 *
	 * <p>조회수는 릴스만 분모·분자에 들어간다(피드는 항상 null) - payload의 viewsNote가 그 규칙을
	 * 모델에게도 명시한다. 좋아요·댓글은 "수집된 것 기준"이라 스냅샷이 아예 없는(아직 미수집) 게시물은
	 * 표본에서 빠지고, 그 표본 수를 각각 *SampleCount로 함께 싣는다.
	 *
	 * <p><b>2026-08-31 groupBy 일반화(스펙 §3-1)</b> - groupBy 생략은 단일 그룹 "all"로 이 메서드가 쓰는
	 * {@link GroupAcc} 누산기를 그대로 태워 기존 스칼라 페이로드({@link #scalarAggregatePayload})로 나간다
	 * (필드명·모양 완전 하위호환). groupBy가 있으면 그룹 페이로드({@link #groupedAggregatePayload})로
	 * 나가며 author 축은 파생 지표(도달 배수·참여율, 스펙 §3-2)까지 서버가 계산해 싣는다.
	 */
	/** 집계 상수(2026-08-31 groupBy 일반화, 스펙 §3-1) - 그룹 행은 캡션 등 자유 텍스트가 없는 순수 숫자
	 * 행(행당 60~80토큰)이라 list_posts 30건 상한보다 여유 있게 잡는다. */
	private static final int DEFAULT_GROUP_LIMIT = 10;
	private static final int MAX_GROUP_LIMIT = 50;
	private static final Set<String> GROUP_BY_VALUES = Set.of("author", "month", "week", "sponsorship", "mediaType");

	private AiToolResult aggregatePosts(ToolSession session, long userId, JsonNode args) {
		long requestedBrandId = args.path("brandId").asLong(0);
		Optional<AiToolResult> mismatch = scopeMismatch(session, requestedBrandId);
		if (mismatch.isPresent()) {
			return mismatch.get();
		}
		Optional<BrandLinkRow> linkOpt = ownedBrand(userId, requestedBrandId);
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

		JsonNode groupByNode = args.path("groupBy");
		String groupBy = groupByNode.isMissingNode() || groupByNode.isNull() ? null : groupByNode.asString();
		if (groupBy != null && !GROUP_BY_VALUES.contains(groupBy)) {
			return error("groupBy는 author·month·week·sponsorship·mediaType 중 하나여야 합니다.");
		}
		String orderBy = args.path("orderBy").asString("postCount");
		int groupLimit = Math.clamp(args.path("limit").asInt(DEFAULT_GROUP_LIMIT), 1, MAX_GROUP_LIMIT);

		// keyword 필터(스펙 §3-1) - search_posts와 같은 정규화(공백 흡수)·같은 매칭 헬퍼를 공유한다.
		String keyword = args.path("keyword").asString("").replace(" ", "");
		List<PostRef> universe = keyword.isEmpty() ? window.inWindow() : captionMatchedRefs(window, keyword);

		Set<String> poolCodes = universe.stream().map(PostRef::shortcode)
				.filter(window.index().poolCodes()::contains)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, BrandReadRepository.LatestMetricsRow> metricsByCode = brandReadRepository
				.findLatestMetricsByShortCodes(poolCodes).stream()
				.collect(Collectors.toMap(BrandReadRepository.LatestMetricsRow::shortCode, Function.identity(),
						(a, b) -> a));

		Function<PostRef, String> keyFn = groupKeyFunction(groupBy);
		Map<String, GroupAcc> groups = new LinkedHashMap<>();
		long skippedNoKey = 0;
		for (PostRef ref : universe) {
			String key = keyFn.apply(ref);
			if (key == null) {
				skippedNoKey++;
				continue;
			}
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
			groups.computeIfAbsent(key, GroupAcc::new).add(code, contentType, views, likes, comments);
		}

		// author 축 팔로워 join(스펙 §3-2) - 기존 배치 조회를 그대로 재사용한다.
		if ("author".equals(groupBy) && !groups.isEmpty()) {
			Map<String, AuthorRow> byUsername = findAuthorsByUsernameBatched(new LinkedHashSet<>(groups.keySet()))
					.stream().collect(Collectors.toMap(AuthorRow::username, Function.identity(), (a, b) -> a));
			for (GroupAcc acc : groups.values()) {
				AuthorRow author = byUsername.get(acc.key);
				acc.followers = author == null ? null : author.followers();
			}
		}

		if (groupBy == null) {
			return scalarAggregatePayload(link, window, keyword, groups, universe.size());
		}
		return groupedAggregatePayload(link, window, groupBy, orderBy, groupLimit, keyword, groups, universe.size(),
				skippedNoKey);
	}

	/** 그룹 1개의 누산기(2026-08-31 groupBy 일반화, 스펙 §3-1·§3-2) - 스칼라 경로도 단일 그룹("all")로
	 * 이 누산기를 쓴다. */
	private static final class GroupAcc {
		final String key;
		long postCount;
		long reelsCount;
		long feedCount;
		long totalViews;               // 릴스만(피드는 조회수 항상 null)
		long viewsSampleCount;
		long totalLikes;
		long likesSampleCount;
		long totalComments;
		long commentsSampleCount;
		long reelsComments;            // engagementRate 분자 - 릴스 게시물의 댓글 합(분모와 모수 일치)
		long reelsCommentsSampleCount;
		String topShortCode;
		Long topViews;
		Long followers;                // author 축 전용(배치 조회로 나중에 채움)

		GroupAcc(String key) {
			this.key = key;
		}

		void add(String shortCode, String contentType, Long views, Long likes, Long comments) {
			postCount++;
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
					topShortCode = shortCode;
				}
			}
			if (isReels && comments != null) {
				reelsComments += comments;
				reelsCommentsSampleCount++;
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

		Double avgViews() {
			return viewsSampleCount == 0 ? null : (double) totalViews / viewsSampleCount;
		}

		Double avgLikes() {
			return likesSampleCount == 0 ? null : (double) totalLikes / likesSampleCount;
		}

		Double avgComments() {
			return commentsSampleCount == 0 ? null : (double) totalComments / commentsSampleCount;
		}

		/** 도달 배수(스펙 §3-2) = 릴스 평균 조회수 ÷ 팔로워. 팔로워 null·0이면 null(계산 불가 - 제외가
		 * 아니라 유지, 정렬 시 nullsLast). */
		Double reachMultiple() {
			Double avg = avgViews();
			return followers == null || followers <= 0 || avg == null ? null : avg / followers;
		}

		/** 참여율(스펙 §3-2) = 릴스 댓글 합 ÷ 릴스 조회수 합. 분모 0·표본 없음이면 null. */
		Double engagementRate() {
			return totalViews <= 0 || reelsCommentsSampleCount == 0 ? null : (double) reelsComments / totalViews;
		}
	}

	/** groupBy 축별 그룹 키(스펙 §3-1) - null 반환은 "키를 정할 수 없는 게시물"(작성자·업로드일 미상)로,
	 * 집계에서 빼고 skippedNoKey로 센다. 기간 버킷은 KST 달력 기준(월은 1일~말일, 주는 월요일 시작) -
	 * "지난달"의 자연스러운 의미와 일치시키고 롤링 30일 해석을 배제한다(스펙 §3-1). {@link
	 * PostRef#uploadedOn()}은 이미 KST 달력일 {@link LocalDate}다({@link
	 * BrandPostAssembler#indexForBrand} 참조) - 별도 타임존 변환이 필요 없다. */
	private static Function<PostRef, String> groupKeyFunction(String groupBy) {
		if (groupBy == null) {
			return ref -> "all";
		}
		return switch (groupBy) {
			case "author" -> PostRef::authorUsername;
			case "month" -> ref -> ref.uploadedOn() == null ? null
					: String.format(Locale.ROOT, "%04d-%02d", ref.uploadedOn().getYear(),
							ref.uploadedOn().getMonthValue());
			case "week" -> ref -> ref.uploadedOn() == null ? null
					: ref.uploadedOn().with(java.time.DayOfWeek.MONDAY).toString();
			case "sponsorship" -> ref -> ref.sponsorship() == null ? "unknown" : ref.sponsorship();
			case "mediaType" -> ref -> ref.contentType() == null ? "unknown"
					: ref.contentType().toLowerCase(Locale.ROOT);
			default -> throw new IllegalArgumentException("알 수 없는 groupBy: " + groupBy); // 호출부가 사전 검증
		};
	}

	/** groupBy 없는 기존 스칼라 페이로드(하위호환 고정, 2026-08-28 신설 당시 필드명·모양 그대로) - 단일
	 * GroupAcc("all")를 펴서 낸다. keyword가 있으면 모델이 "키워드 매칭 게시물 기준"임을 답변에 밝힐 수
	 * 있게 명시한다. */
	private AiToolResult scalarAggregatePayload(BrandLinkRow link, BrandWindow window, String keyword,
			Map<String, GroupAcc> groups, int universeSize) {
		GroupAcc acc = groups.getOrDefault("all", new GroupAcc("all"));
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("brandId", link.brandId());
		payload.put("since", window.since().toString());
		payload.put("window", window.windowKind());
		if (!keyword.isEmpty()) {
			payload.put("keyword", keyword);
		}
		payload.put("postCount", universeSize);
		payload.put("reelsCount", acc.reelsCount);
		payload.put("feedCount", acc.feedCount);
		payload.put("viewsNote", "피드 게시물은 조회수가 항상 null이라 조회수 집계·평균은 릴스만 대상입니다.");
		payload.put("totalViews", acc.totalViews);
		payload.put("avgViews", acc.avgViews());
		payload.put("viewsSampleCount", acc.viewsSampleCount);
		payload.put("totalLikes", acc.totalLikes);
		payload.put("avgLikes", acc.avgLikes());
		payload.put("likesSampleCount", acc.likesSampleCount);
		payload.put("totalComments", acc.totalComments);
		payload.put("avgComments", acc.avgComments());
		payload.put("commentsSampleCount", acc.commentsSampleCount);
		List<String> codes;
		if (acc.topShortCode != null) {
			ObjectNode topPost = payload.putObject("topPost");
			topPost.put("shortCode", acc.topShortCode);
			topPost.put("views", acc.topViews);
			codes = List.of(acc.topShortCode);
		} else {
			codes = List.of();
		}
		return AiToolResult.ok(payload.toString(), universeSize, codes);
	}

	/** groupBy가 있는 그룹 페이로드(스펙 §3-1·§3-2) - 정렬은 서버가 orderBy 기준 내림차순으로 하고
	 * (nullsLast), limit은 반환 그룹 수만 자른다. totalGroups는 절단과 무관하게 전체 그룹 수를 그대로
	 * 보고해 "전체 N개 중 상위 M개" 같은 조용한 절단을 막는다. */
	private AiToolResult groupedAggregatePayload(BrandLinkRow link, BrandWindow window, String groupBy,
			String orderBy, int groupLimit, String keyword, Map<String, GroupAcc> groups, int universeSize,
			long skippedNoKey) {
		Function<GroupAcc, Double> sortKey = switch (orderBy) {
			case "totalViews" -> acc -> (double) acc.totalViews;
			case "avgViews" -> GroupAcc::avgViews;
			case "avgLikes" -> GroupAcc::avgLikes;
			case "avgComments" -> GroupAcc::avgComments;
			case "reachMultiple" -> GroupAcc::reachMultiple;
			case "engagementRate" -> GroupAcc::engagementRate;
			default -> acc -> (double) acc.postCount; // postCount 및 알 수 없는 값 폴백
		};
		List<GroupAcc> ordered = groups.values().stream()
				.sorted(Comparator.comparing(sortKey, Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();
		List<GroupAcc> page = ordered.stream().limit(groupLimit).toList();

		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("brandId", link.brandId());
		payload.put("since", window.since().toString());
		payload.put("window", window.windowKind());
		payload.put("groupBy", groupBy);
		payload.put("orderBy", orderBy);
		if (!keyword.isEmpty()) {
			payload.put("keyword", keyword);
		}
		payload.put("postCount", universeSize);
		payload.put("totalGroups", groups.size());
		payload.put("returnedGroups", page.size());
		if (skippedNoKey > 0) {
			payload.put("skippedNoKey", skippedNoKey);
		}
		payload.put("viewsNote", "피드 게시물은 조회수가 항상 null이라 조회수·도달배수·참여율은 릴스만 대상입니다. "
				+ "reachMultiple·engagementRate는 서버가 계산한 값이니 그대로 인용하세요.");
		ArrayNode groupsNode = payload.putArray("groups");
		List<String> codes = new ArrayList<>();
		for (GroupAcc acc : page) {
			ObjectNode node = groupsNode.addObject();
			node.put("key", acc.key);
			node.put("postCount", acc.postCount);
			node.put("reelsCount", acc.reelsCount);
			node.put("feedCount", acc.feedCount);
			node.put("totalViews", acc.totalViews);
			node.put("avgViews", acc.avgViews());
			node.put("viewsSampleCount", acc.viewsSampleCount);
			node.put("totalLikes", acc.totalLikes);
			node.put("avgLikes", acc.avgLikes());
			node.put("totalComments", acc.totalComments);
			node.put("avgComments", acc.avgComments());
			if ("author".equals(groupBy)) {
				node.put("followers", acc.followers);
				node.put("reachMultiple", acc.reachMultiple());
				node.put("engagementRate", acc.engagementRate());
			}
			if (acc.topShortCode != null) {
				node.put("topPostShortCode", acc.topShortCode);
				if (codes.size() < MAX_GROUP_LIMIT) {
					codes.add(acc.topShortCode);
				}
			}
		}
		// rowCount는 다른 툴과 달리 반환 그룹 수가 아니라 전체 그룹 수다 - search_posts의 totalMatches
		// 관용구와 동일하게 "정확한 총 수"를 로그(app.ai_chat_logs.tool_calls[].rows)에도 남긴다.
		return AiToolResult.ok(payload.toString(), groups.size(), codes);
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

	/**
	 * 댓글(C1, 배치화 2026-08-31 스펙 §3-3) - shortCodes 배열이 있으면 최대 {@value #MAX_COMMENT_POSTS}개
	 * 게시물의 댓글을 1회 호출로 묶어 돌려준다(여러 게시물 여론 종합 시 게시물마다 따로 부르는 왕복을
	 * 없앤다). shortCodes가 없으면(모델 이력 호환) 기존 단건 경로({@link #getCommentsSingle})로 그대로
	 * 위임한다 - 페이로드 모양이 배열 경로와 달라 모델이 예전 관용구를 써도 응답 파싱이 깨지지 않는다.
	 */
	private AiToolResult getComments(ToolSession session, long userId, JsonNode args) {
		JsonNode codesNode = args.path("shortCodes");
		if (!codesNode.isArray() || codesNode.isEmpty()) {
			return getCommentsSingle(session, userId, args);
		}
		List<String> shortCodes = new ArrayList<>();
		for (JsonNode codeNode : codesNode) {
			String code = codeNode.asString("");
			if (!code.isBlank() && !shortCodes.contains(code)) {
				shortCodes.add(code);
			}
			if (shortCodes.size() >= MAX_COMMENT_POSTS) {
				break;
			}
		}
		if (shortCodes.isEmpty()) {
			return error("shortCodes가 비어 있습니다.");
		}
		int perPost = Math.clamp(args.path("limit").asInt(PER_POST_DEFAULT_COMMENTS), 1, PER_POST_MAX_COMMENTS);

		ObjectNode payload = objectMapper.createObjectNode();
		ArrayNode postsNode = payload.putArray("posts");
		ArrayNode notFound = objectMapper.createArrayNode();
		List<String> okCodes = new ArrayList<>();
		int total = 0;
		for (String shortCode : shortCodes) {
			if (total >= TOTAL_BATCH_COMMENTS) {
				break;
			}
			Optional<BrandPostResponse> found = hydrateOwnedPost(session, userId, shortCode, true);
			if (found.isEmpty()) {
				notFound.add(shortCode);
				continue;
			}
			List<TrackingItemResponse.PostCommentResponse> rows = found.get().recentComments().stream()
					.limit(Math.min(perPost, TOTAL_BATCH_COMMENTS - total)).toList();
			total += rows.size();
			okCodes.add(shortCode);
			ObjectNode postNode = postsNode.addObject();
			postNode.put("shortCode", shortCode);
			postNode.put("returned", rows.size());
			ArrayNode comments = postNode.putArray("comments");
			for (TrackingItemResponse.PostCommentResponse row : rows) {
				ObjectNode node = comments.addObject();
				node.put("author", row.author());
				node.put("body", truncate(row.text(), COMMENT_BODY_LENGTH));
				node.put("likeCount", row.likes());
				node.put("commentedAt", row.createdAt());
				node.put("ownerReplyText", row.reply() == null ? null : row.reply().text());
			}
		}
		payload.put("totalReturned", total);
		if (!notFound.isEmpty()) {
			payload.set("notFound", notFound);
		}
		if (okCodes.isEmpty()) {
			return error("어느 게시물도 이 사용자의 브랜드 게시물 목록에 없거나 접근 권한이 없습니다. list_posts로 확인하세요.");
		}
		return AiToolResult.ok(payload.toString(), total, okCodes);
	}

	/** 기존 단건 경로(하위호환, 스펙 §3-3) - shortCodes 배열 없이 shortCode 하나로 부르는 기존 관용구를
	 * 그대로 유지한다. {@link #hydrateOwnedPost}로 소유·창 검증을 거친 뒤 이미 하이드레이트된 댓글만 자른다. */
	private AiToolResult getCommentsSingle(ToolSession session, long userId, JsonNode args) {
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
	 * 해시태그 발견 목록(I2, F6 2026-08-30 리뷰) - {@link BrandHashtagPostAssembler#assembleForBrand}가
	 * tagged 겹침 제외·조회자 본인 태그 원장 교집합 필터·조회자 스코프 brandPostId를 전부 강제한다.
	 * 이 메서드는 모델 요청 창(days)에 더해 scope의 날짜·작성자 검색(q)·팔로워 범위 축까지 적용한다 -
	 * mediaType·source·sponsorship 판정만 여전히 없다(해시태그 발견 게시물은 브랜드 계정 풀 데이터가
	 * 아니라 그 세 축의 원천 컬럼 자체가 없다).
	 */
	private AiToolResult listHashtagPosts(ToolSession session, long userId, JsonNode args) {
		long requestedBrandId = args.path("brandId").asLong(0);
		Optional<AiToolResult> mismatch = scopeMismatch(session, requestedBrandId);
		if (mismatch.isPresent()) {
			return mismatch.get();
		}
		Optional<BrandLinkRow> linkOpt = ownedBrand(userId, requestedBrandId);
		if (linkOpt.isEmpty()) {
			return error("그 브랜드는 이 사용자의 모니터링 목록에 없거나 접근 권한이 없습니다. list_brands로 확인하세요.");
		}
		BrandLinkRow link = linkOpt.get();
		List<BrandHashtagPostResponse> all = hashtagPostAssembler.assembleForBrand(userId, link.brandId());

		LocalDate cutoff = cutoffDateFor(link, args);
		AiScope scope = session.scope();
		List<BrandHashtagPostResponse> dateFiltered = all.stream()
				.filter(row -> row.takenAt() != null && !OffsetDateTime.parse(row.takenAt()).toLocalDate().isBefore(cutoff))
				// FE scope 강제(T3, 2026-08-30) - 해시태그 발견 게시물은 브랜드 계정 스코프 데이터가
				// 아니라(source·sponsorship·mediaType 판정이 없다) 날짜 축만 스트림에서 적용한다.
				.filter(row -> withinScopeDate(row, scope))
				.toList();
		// q·팔로워 축(F6) - author_profile 배치 조회가 필요해 목록 단위로 뺐다(applyAuthorScope와 동형).
		List<BrandHashtagPostResponse> scoped = applyHashtagAuthorScope(dateFiltered, scope);
		List<BrandHashtagPostResponse> page = scoped.stream()
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
	 * 세션 brandId 고정 검사(F1, 2026-08-30 리뷰) - brandId를 인자로 받는 툴(list_posts·search_posts·
	 * aggregate_posts·list_hashtag_posts) 전부가 실제 쿼리를 태우기 전에 이 검사부터 거친다. 소유
	 * 검증({@link #ownedBrand})은 "이 유저의 브랜드인가"만 보므로, 유저가 own·competitor 여러 브랜드를
	 * 모니터링 중이면 다른 소유 브랜드로도 통과한다 - 이 대화가 스코프된 계정(세션 brandId)을 벗어나는
	 * 걸 막는 건 이 검사의 몫이다. 예외를 던지지 않고 실패 결과를 돌려줘 모델이 다음 호출에서 brandId를
	 * 세션 값으로 고쳐 부르게 유도한다(설계 §8과 같은 자가 수정 관용구). 세션에 brandId가 없거나
	 * (단발 테스트) 요청 brandId가 아예 없으면(0 이하 - 곧이어 ownedBrand가 그 케이스를 처리한다)
	 * 검사를 건너뛴다.
	 */
	private Optional<AiToolResult> scopeMismatch(ToolSession session, long requestedBrandId) {
		Long sessionBrandId = session.brandId();
		if (sessionBrandId != null && requestedBrandId > 0 && sessionBrandId != requestedBrandId) {
			return Optional.of(error("이 대화는 브랜드 " + sessionBrandId + "로 고정되어 있습니다. "
					+ "brandId=" + sessionBrandId + "로 다시 호출하세요."));
		}
		return Optional.empty();
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
		AiScope scope = session.scope();
		// 세션 brandId 고정(F1, 2026-08-30 리뷰) - get_post·get_comments는 shortCode만 받아 brandId 인자가
		// 없다. 예전엔 유저의 활성 링크 전부를 순회해 이 대화가 스코프되지 않은 다른 브랜드의 shortCode도
		// 조회 대상에 들었다 - 세션에 brandId가 고정돼 있으면 그 브랜드 링크 1건으로만 후보를 좁힌다.
		List<BrandLinkRow> candidates = session.brandId() == null ? linkRepository.findAllActiveByUser(userId)
				: linkRepository.findActiveByUserAndBrand(userId, session.brandId()).map(List::of).orElseGet(List::of);
		for (BrandLinkRow link : candidates) {
			Optional<BrandAccountRow> accountOpt = brandReadRepository.findAccount(link.brandId());
			if (accountOpt.isEmpty()) {
				continue;
			}
			LocalDate windowStart = today.minusMonths(link.collectionMonths());
			BrandPostIndex index = indexFor(session, userId, accountOpt.get(), false);
			// FE scope 강제(T3, 2026-08-30) - 상세 접근은 링크 창 판정만 타지만(N1), scope는 화면 필터라
			// 예외 없이 여기서도 걸린다. scope 밖 shortCode는 "범위 밖"으로 취급해 아래와 같은 실패
			// 결과가 나가게 존재하지 않는 것처럼 건너뛴다(호출부가 이미 "없거나 권한 없음" 메시지를 쓴다).
			Optional<PostRef> refOpt = index.refs().stream()
					.filter(r -> r.shortcode().equals(shortCode) && withinLinkWindow(r, windowStart))
					.findFirst();
			if (refOpt.isEmpty() || !matchesScope(refOpt.get(), scope) || !authorMatchesScope(refOpt.get(), scope)) {
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

	/** {@link #listHashtagPosts} 전용 scope 날짜 판정 - row.takenAt()이 이미 non-null임을 호출부가 보장한다. */
	private static boolean withinScopeDate(BrandHashtagPostResponse row, AiScope scope) {
		if (scope == null || (scope.dateFrom() == null && scope.dateTo() == null)) {
			return true;
		}
		LocalDate date = OffsetDateTime.parse(row.takenAt()).toLocalDate();
		if (scope.dateFrom() != null && date.isBefore(scope.dateFrom())) {
			return false;
		}
		return scope.dateTo() == null || !date.isAfter(scope.dateTo());
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
