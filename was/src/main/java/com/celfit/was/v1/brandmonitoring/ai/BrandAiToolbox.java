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
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 툴 6종 실행기(설계 §4) - 전부 읽기 전용이고, <b>brandId·shortCode 소유 검증이 이 클래스 안에서
 * 강제된다</b>. LLM이 임의 id를 넘길 수 있다는 전제로 짜여 있으며, 검증에 걸리면 예외가 아니라
 * failed 결과를 돌려 모델이 스스로 물러나게 한다.
 *
 * <p><b>2026-08-27 격리 계통 재배치(리뷰 C1/I2/I3/I4/I9)</b> — 브랜드 풀은 유저 간 공유라 원시
 * {@link BrandReadRepository} 직접 호출로는 FE가 강제하는 유저별 가시성 필터(direct-only 게시물은
 * 등록자 전용)·표시 창(collectionMonths) 검사·경쟁사 광고 판정 억제를 건너뛴다. 게시물 관련 툴
 * (list_posts·get_post·get_comments·list_hashtag_posts)은 전부 FE와 같은 조립 경로 —
 * {@link BrandPostAssembler#indexForBrand}·{@link BrandPostAssembler#hydrate}·
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

	private static final String SORT_PERFORMANCE_DESC = "performance_desc";
	/** {@link BrandPostAssembler#SOURCE_DIRECT}의 패키지 밖 사본 - 그쪽 상수는 패키지 전용이라 여기서
	 * 새로 선언한다({@link PostRef#source()} 값과 리터럴로 비교). */
	private static final String SOURCE_DIRECT = "direct";

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

	public AiToolResult execute(long userId, String toolName, JsonNode args) {
		return switch (toolName) {
			case BrandAiToolSpecs.LIST_BRANDS -> listBrands(userId);
			case BrandAiToolSpecs.LIST_POSTS -> listPosts(userId, args);
			case BrandAiToolSpecs.GET_POST -> getPost(userId, args);
			case BrandAiToolSpecs.GET_COMMENTS -> getComments(userId, args);
			case BrandAiToolSpecs.LIST_HASHTAG_POSTS -> listHashtagPosts(userId, args);
			case BrandAiToolSpecs.GET_AUTHOR -> getAuthor(args);
			default -> error("알 수 없는 툴입니다: " + toolName);
		};
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
	 * 목록(C1/I3/I9) - 컨트롤러 목록과 같은 2단 조립(indexForBrand + hydrate)을 탄다. 인덱스가
	 * 이미 노출 필터(direct-only 등록자 전용, {@link BrandPostAssembler#indexForBrand} 참조)를 강제해
	 * 원시 리포지토리 호출로는 새던 겹침 게시물이 여기선 애초에 후보에 없다. ENRICHED_ONLY도
	 * indexForBrand 고정값이라 별도 스코프 인자가 필요 없다(I3).
	 */
	private AiToolResult listPosts(long userId, JsonNode args) {
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
		BrandPostIndex index = postAssembler.indexForBrand(userId, account, performanceSort);

		LocalDate cutoff = cutoffDateFor(link, args);
		List<PostRef> inWindow = index.refs().stream().filter(r -> withinLinkWindow(r, cutoff)).toList();

		Comparator<PostRef> order = performanceSort
				? Comparator.comparing(PostRef::latestViews, Comparator.nullsLast(Comparator.reverseOrder()))
				: Comparator.comparing(PostRef::uploadedOn, Comparator.nullsLast(Comparator.reverseOrder()));
		List<PostRef> page = inWindow.stream().sorted(order).limit(MAX_POSTS).toList();
		List<String> codes = page.stream().map(PostRef::shortcode).toList();

		// hydrate 반환 순서는 입력 codes 순서와 같다(BrandPostAssembler#hydrate 계약) - 위에서 이미
		// 정렬·페이지 슬라이스를 끝냈으니 재정렬이 필요 없다. 목록 표면 계약대로 댓글은 싣지 않는다.
		List<BrandPostResponse> posts = postAssembler.hydrate(userId, account, link.accountType(), index, codes,
				false);

		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("brandId", link.brandId());
		payload.put("since", cutoff.toString());
		payload.put("returned", posts.size());
		payload.put("totalInWindow", inWindow.size());
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
	 * 상세(C1/I4) - {@link #hydrateOwnedPost}가 컨트롤러 {@code get()}과 같은 관용구(유저 링크 순회 →
	 * indexForBrand → 창 검사 → hydrate)로 소유·창을 검증한다. 광고 표기 노출(토글+경쟁사 억제)은
	 * {@link BrandPostAssembler#brandPost} 게이트를 그대로 물려받는다(I4) - 여기선 값이 있으면 싣기만
	 * 한다.
	 */
	private AiToolResult getPost(long userId, JsonNode args) {
		String shortCode = args.path("shortCode").asString();
		Optional<BrandPostResponse> found = hydrateOwnedPost(userId, shortCode, true);
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
	private AiToolResult getComments(long userId, JsonNode args) {
		String shortCode = args.path("shortCode").asString();
		Optional<BrandPostResponse> found = hydrateOwnedPost(userId, shortCode, true);
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
			node.put("body", row.text());
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
	private Optional<BrandPostResponse> hydrateOwnedPost(long userId, String shortCode, boolean withComments) {
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
			BrandPostIndex index = postAssembler.indexForBrand(userId, accountOpt.get(), false);
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
	 * 창과 무관하게 통과한다. 나머지는 업로드일이 컷 이상이어야 한다(업로드일 미상은 제외).
	 */
	private static boolean withinLinkWindow(PostRef ref, LocalDate cutoff) {
		return SOURCE_DIRECT.equals(ref.source())
				|| (ref.uploadedOn() != null && !ref.uploadedOn().isBefore(cutoff));
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
