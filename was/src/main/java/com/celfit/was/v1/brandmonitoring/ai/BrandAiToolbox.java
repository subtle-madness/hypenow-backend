package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.AuthorRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandCommentRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandHashtagPostRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandPostIndexRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandPostMetaRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandSnapshotRow;
import com.celfit.was.monitoring.BrandReadRepository.LatestViewsRow;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <p>{@link BrandReadRepository}는 brandId를 검증 없이 조회한다(그 클래스 javadoc 명시) - 소유
 * 스코프는 호출자 책임이고, 이 클래스가 그 호출자다. 반드시 {@link BrandLinkRepository}에서 얻은
 * brandId만 넘긴다.
 *
 * <p>건수 상한(게시물 30·댓글 50·시계열 14)은 모델 요청값과 무관하게 여기서 자른다(설계 §7) -
 * 토큰 폭발 방지가 목적이라 프롬프트 지시로는 보장할 수 없다.
 */
public class BrandAiToolbox {

	/** 게시물 목록 상한 - 30건이면 "최근 흐름"을 판단하기 충분하고 캡션 발췌 포함 토큰이 통제된다. */
	private static final int MAX_POSTS = 30;
	/** 댓글 상한(설계 §7). */
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

	private final BrandLinkRepository linkRepository;
	private final BrandReadRepository brandReadRepository;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final boolean exposeAdDisclosure;

	public BrandAiToolbox(BrandLinkRepository linkRepository, BrandReadRepository brandReadRepository,
			ObjectMapper objectMapper, Clock clock, boolean exposeAdDisclosure) {
		this.linkRepository = linkRepository;
		this.brandReadRepository = brandReadRepository;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.exposeAdDisclosure = exposeAdDisclosure;
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

	private AiToolResult listPosts(long userId, JsonNode args) {
		Optional<BrandLinkRow> link = ownedBrand(userId, args.path("brandId").asLong(0));
		if (link.isEmpty()) {
			return error("그 브랜드는 이 사용자의 모니터링 목록에 없거나 접근 권한이 없습니다. list_brands로 확인하세요.");
		}
		OffsetDateTime cutoff = cutoffFor(link.get(), args);
		List<BrandPostIndexRow> index = brandReadRepository.findBrandPostIndex(link.get().brandId(), cutoff, false);
		Map<String, Long> viewsByCode = new HashMap<>();
		for (LatestViewsRow row : brandReadRepository.findLatestViewsForBrand(link.get().brandId(), cutoff, false)) {
			viewsByCode.put(row.shortCode(), row.views());
		}
		Comparator<BrandPostIndexRow> order = SORT_PERFORMANCE_DESC.equals(args.path("sort").asString())
				// 조회수 없는 게시물(피드는 views가 항상 null)이 성과순 앞자리를 차지하지 않도록 0으로 접는다
				? Comparator.comparingLong((BrandPostIndexRow row) -> viewsOf(viewsByCode, row.shortCode()))
						.reversed()
				: Comparator.comparing(BrandPostIndexRow::takenAt,
						Comparator.nullsLast(Comparator.reverseOrder()));
		List<BrandPostIndexRow> page = index.stream().sorted(order).limit(MAX_POSTS).toList();

		List<String> codes = page.stream().map(BrandPostIndexRow::shortCode).toList();
		Map<String, BrandSnapshotRow> latest = latestSnapshotByCode(codes);
		Map<String, BrandPostMetaRow> metaByCode = new HashMap<>();
		for (BrandPostMetaRow meta : brandReadRepository.findPostMeta(codes)) {
			metaByCode.put(meta.shortCode(), meta);
		}

		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("brandId", link.get().brandId());
		payload.put("since", cutoff.toString());
		payload.put("returned", page.size());
		payload.put("totalInWindow", index.size());
		ArrayNode posts = payload.putArray("posts");
		for (BrandPostIndexRow row : page) {
			ObjectNode node = posts.addObject();
			node.put("shortCode", row.shortCode());
			node.put("takenAt", row.takenAt() == null ? null : row.takenAt().toString());
			node.put("isPaidPartnership", row.isPaidPartnership());
			node.put("caption", truncate(row.caption(), CAPTION_EXCERPT_LENGTH));
			BrandPostMetaRow meta = metaByCode.get(row.shortCode());
			node.put("authorUsername", meta == null ? null : meta.username());
			BrandSnapshotRow snapshot = latest.get(row.shortCode());
			node.put("likes", snapshot == null ? null : snapshot.likes());
			node.put("comments", snapshot == null ? null : snapshot.comments());
			node.put("views", snapshot == null ? null : snapshot.views());
		}
		return AiToolResult.ok(payload.toString(), page.size(), codes);
	}

	private AiToolResult getPost(long userId, JsonNode args) {
		String shortCode = args.path("shortCode").asString();
		if (ownerBrandOf(userId, shortCode).isEmpty()) {
			return error("그 게시물은 이 사용자의 브랜드 게시물 목록에 없거나 접근 권한이 없습니다. list_posts로 확인하세요.");
		}
		List<BrandPostMetaRow> metas = brandReadRepository.findPostMeta(List.of(shortCode));
		if (metas.isEmpty()) {
			return error("그 게시물의 상세 정보가 아직 수집되지 않았습니다.");
		}
		BrandPostMetaRow meta = metas.get(0);
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("shortCode", meta.shortCode());
		payload.put("authorUsername", meta.username());
		payload.put("contentType", meta.contentType());
		payload.put("uploadedAt", meta.uploadedAt() == null ? null : meta.uploadedAt().toString());
		payload.put("caption", truncate(meta.caption(), CAPTION_FULL_LENGTH));
		payload.put("isPaidPartnership", meta.isPaidPartnership());
		if (exposeAdDisclosure) {
			payload.put("adDisclosure", meta.adVerdict());
		}
		ArrayNode metrics = payload.putArray("dailyMetrics");
		List<BrandSnapshotRow> snapshots = brandReadRepository.findSnapshots(List.of(shortCode));
		List<BrandSnapshotRow> tail = snapshots.size() <= MAX_SNAPSHOTS
				? snapshots : snapshots.subList(snapshots.size() - MAX_SNAPSHOTS, snapshots.size());
		for (BrandSnapshotRow snapshot : tail) {
			ObjectNode node = metrics.addObject();
			node.put("capturedOn", snapshot.capturedOn().toString());
			node.put("likes", snapshot.likes());
			node.put("comments", snapshot.comments());
			node.put("views", snapshot.views());
		}
		return AiToolResult.ok(payload.toString(), 1, List.of(shortCode));
	}

	private AiToolResult getComments(long userId, JsonNode args) {
		String shortCode = args.path("shortCode").asString();
		if (ownerBrandOf(userId, shortCode).isEmpty()) {
			return error("그 게시물은 이 사용자의 브랜드 게시물 목록에 없거나 접근 권한이 없습니다. list_posts로 확인하세요.");
		}
		int requested = args.path("limit").asInt(DEFAULT_COMMENTS);
		int limit = Math.clamp(requested, 1, MAX_COMMENTS);
		List<BrandCommentRow> rows = brandReadRepository.findComments(List.of(shortCode), limit);
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("shortCode", shortCode);
		payload.put("limit", limit);
		payload.put("returned", rows.size());
		ArrayNode comments = payload.putArray("comments");
		for (BrandCommentRow row : rows) {
			ObjectNode node = comments.addObject();
			node.put("author", row.author());
			node.put("body", row.body());
			node.put("likeCount", row.likeCount());
			node.put("commentedAt", row.commentedAt() == null ? null : row.commentedAt().toString());
			node.put("ownerReplyText", row.ownerReplyText());
		}
		return AiToolResult.ok(payload.toString(), rows.size(), List.of(shortCode));
	}

	private AiToolResult listHashtagPosts(long userId, JsonNode args) {
		Optional<BrandLinkRow> link = ownedBrand(userId, args.path("brandId").asLong(0));
		if (link.isEmpty()) {
			return error("그 브랜드는 이 사용자의 모니터링 목록에 없거나 접근 권한이 없습니다. list_brands로 확인하세요.");
		}
		List<BrandHashtagPostRow> rows = brandReadRepository.findHashtagPosts(
				link.get().brandId(), cutoffFor(link.get(), args), MAX_HASHTAG_POSTS);
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("brandId", link.get().brandId());
		payload.put("returned", rows.size());
		ArrayNode posts = payload.putArray("posts");
		List<String> codes = new ArrayList<>();
		for (BrandHashtagPostRow row : rows) {
			codes.add(row.shortCode());
			ObjectNode node = posts.addObject();
			node.put("shortCode", row.shortCode());
			node.put("matchedTag", row.matchedTag());
			node.put("authorUsername", row.authorUsername());
			node.put("takenAt", row.takenAt() == null ? null : row.takenAt().toString());
			node.put("caption", truncate(row.caption(), CAPTION_EXCERPT_LENGTH));
			node.put("likes", row.likes());
			node.put("comments", row.comments());
		}
		return AiToolResult.ok(payload.toString(), rows.size(), codes);
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
	 * shortCode가 이 유저의 어느 브랜드 게시물 풀(tagged ∪ direct)에 속하는지 - 속하지 않으면 empty다.
	 * 브랜드 수는 유저당 최대 9개(own 6 + competitor 3)라 순회 비용이 상한선 안에 있다.
	 */
	private Optional<Long> ownerBrandOf(long userId, String shortCode) {
		if (shortCode == null || shortCode.isBlank()) {
			return Optional.empty();
		}
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(userId)) {
			if (!brandReadRepository.findBrandPostsByShortCodes(link.brandId(), List.of(shortCode)).isEmpty()) {
				return Optional.of(link.brandId());
			}
		}
		return Optional.empty();
	}

	/** 모델이 요청한 days와 유저의 표시 기간(collectionMonths) 중 짧은 쪽 - 창 밖 데이터는 보이면 안 된다. */
	private OffsetDateTime cutoffFor(BrandLinkRow link, JsonNode args) {
		int days = Math.clamp(args.path("days").asInt(DEFAULT_DAYS), 1, MAX_DAYS);
		OffsetDateTime now = OffsetDateTime.now(clock);
		OffsetDateTime requested = now.minusDays(days);
		OffsetDateTime windowStart = now.minusMonths(link.collectionMonths());
		return requested.isAfter(windowStart) ? requested : windowStart;
	}

	/** 성과순 정렬 키 - 조회수 미수집(피드는 항상 null)은 0으로 접어 뒤로 보낸다. */
	private static long viewsOf(Map<String, Long> viewsByCode, String shortCode) {
		Long views = viewsByCode.get(shortCode);
		return views == null ? 0L : views;
	}

	private Map<String, BrandSnapshotRow> latestSnapshotByCode(List<String> shortCodes) {
		Map<String, BrandSnapshotRow> latest = new HashMap<>();
		if (shortCodes.isEmpty()) {
			return latest;
		}
		// findSnapshots는 capturedOn 오름차순이라 뒤에 오는 행이 항상 더 최신이다
		for (BrandSnapshotRow row : brandReadRepository.findSnapshots(shortCodes)) {
			latest.put(row.shortCode(), row);
		}
		return latest;
	}

	private static String truncate(String text, int max) {
		if (text == null) {
			return null;
		}
		return text.length() <= max ? text : text.substring(0, max) + "...";
	}

	private AiToolResult error(String message) {
		return AiToolResult.failure(objectMapper.createObjectNode().put("error", message).toString());
	}
}
