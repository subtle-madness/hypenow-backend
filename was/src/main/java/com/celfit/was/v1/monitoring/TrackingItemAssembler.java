package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.KeywordRule;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.PostCommentRow;
import com.celfit.was.monitoring.PostMetaRow;
import com.celfit.was.monitoring.ProfileMetaRow;
import com.celfit.was.monitoring.ProfileSnapshotBatchRow;
import com.celfit.was.monitoring.TargetDetailRow;
import com.celfit.was.monitoring.TargetRow;
import com.celfit.was.monitoring.TrackedSnapshotRow;
import com.celfit.was.v1.common.KstTimestamps;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * TrackingItem 완전 조립(계약 6.25·6.26) — GET /v1/monitoring/items(목록)과 6.29(PATCH)·6.30(cancel)
 * 단건 응답이 공용으로 쓴다. 목록 조립은 유저의 전체 행에 필요한 target·post_meta·snapshot·comment·
 * profile_meta를 배치(IN절)로 한 번씩만 조회해 N+1을 피한다(단건 조립은 같은 배치 메서드를 원소
 * 1개짜리 컬렉션으로 재사용 — 성능상 무의미하지만 조립 로직 중복을 없앤다).
 *
 * <p>이 클래스가 흡수한 구 {@code V1MonitoringItemUpdateService} private 헬퍼: fetchTarget·
 * latestFollowers·nextSweepAt(→ {@link MonitoringExpiry#nextSweepAt()}으로 승격)·readKeywordRule.
 */
@Component
public class TrackingItemAssembler {

	// 저장 상한(monitoring comment-pages=3, 45건 — application.yml 주석)과 같은 수준으로 맞춘다.
	// 예전엔 8이라 monitoring이 45건까지 수집해도 화면엔 8건만 보였다(07-31 결함 B) — 저장은
	// 했는데 못 보는 상태를 만들지 않기 위해 서빙 상한을 수집 상한과 동일하게 둔다.
	private static final int COMMENT_LIMIT = 45;
	private static final String MODE_ACCOUNT = "account";
	private static final String MODE_URL = "url";
	private static final String CONTENT_TYPE_REELS = "REELS";

	private final MonitoringItemRepository itemRepository;
	private final CampaignRepository campaignRepository;
	private final Optional<MonitoringReadRepository> readRepository;
	private final ObjectMapper objectMapper;

	public TrackingItemAssembler(MonitoringItemRepository itemRepository, CampaignRepository campaignRepository,
			Optional<MonitoringReadRepository> readRepository, ObjectMapper objectMapper) {
		this.itemRepository = itemRepository;
		this.campaignRepository = campaignRepository;
		this.readRepository = readRepository;
		this.objectMapper = objectMapper;
	}

	/**
	 * GET /v1/monitoring/items(6.26) — 유저 전량 조회 + meta(lastCollectedAt·today).
	 *
	 * <p>monitoring 왕복은 3회(통합 조회 + 스냅샷 + 댓글) — 2026-08-27 풀 대기 진단(31행 max()가
	 * 커넥션 대기로 2.4초까지 부풂)의 수리로, target·post_meta·profile_meta·팔로워·last sweep 5개
	 * 왕복을 {@link MonitoringReadRepository#findTargetDetails}가 한 SQL로 접는다. 스냅샷·댓글은
	 * 행 수가 많아(게시물당 D일·45건) 별도 왕복 유지.
	 */
	public AssembledList assembleList(long userId) {
		List<MonitoringItemRow> items = itemRepository.findByUser(userId);
		Map<Long, CampaignRow> campaignsById = campaignRepository.findByUser(userId).stream()
				.collect(Collectors.toMap(CampaignRow::id, c -> c));

		Set<Long> targetIds = items.stream().map(MonitoringItemRow::targetId).filter(Objects::nonNull)
				.collect(Collectors.toSet());
		List<TargetDetailRow> details = readRepository.map(repo -> repo.findTargetDetails(targetIds))
				.orElseGet(List::of);
		Map<Long, TargetRow> targetsById = details.stream()
				.collect(Collectors.toMap(TargetDetailRow::id, TargetDetailRow::toTargetRow));

		// last_completed_at은 통합 조회의 비상관 스칼라(전 행 동일) — 추적 행이 0개면 폴백 왕복.
		OffsetDateTime lastCollectedAt = details.isEmpty()
				? readRepository.map(MonitoringReadRepository::lastSuccessfulSweepAt).orElse(null)
				: details.get(0).lastCompletedAt();
		LocalDate lastCollectedDate = KstTimestamps.toKstDate(lastCollectedAt);

		Set<String> shortCodes = targetsById.values().stream().map(TargetRow::trackedShortCode)
				.filter(Objects::nonNull).collect(Collectors.toSet());
		PostBundle bundle = buildListBundle(details, shortCodes, lastCollectedDate);

		// meta.today와 pending 만료 판정이 같은 날짜를 봐야 자정 경계에서 어긋나지 않는다.
		LocalDate today = LocalDate.now(KstTimestamps.KST);
		List<TrackingItemResponse> responses = items.stream()
				.map(item -> assembleForList(item, targetsById.get(item.targetId()), campaignsById, bundle, today))
				.toList();

		return new AssembledList(responses, lastCollectedAt, today);
	}

	/**
	 * 6.29(PATCH)·6.30(cancel) 단건 재조립 — 호출부가 이미 조회·확정한 item·target·status·trackingDays·
	 * campaign을 그대로 받는다(status는 취소 직후처럼 item의 canceled_at이 아직 반영 전인 값과
	 * 달라질 수 있어 서비스가 계산해 넘긴다).
	 */
	public TrackingItemResponse assembleSingle(MonitoringItemRow item, TargetRow target, String status,
			int trackingDays, Long campaignId, String campaignName) {
		String shortCode = target == null ? null : target.trackedShortCode();
		String username = target == null ? null : target.username();
		Set<String> shortCodes = shortCode == null ? Set.of() : Set.of(shortCode);
		Set<String> usernames = username == null ? Set.of() : Set.of(username);

		OffsetDateTime lastCollectedAt = readRepository.map(MonitoringReadRepository::lastSuccessfulSweepAt)
				.orElse(null);
		LocalDate lastCollectedDate = KstTimestamps.toKstDate(lastCollectedAt);
		PostBundle bundle = fetchPostBundle(shortCodes, usernames, lastCollectedDate);

		return build(item, target, status, trackingDays, campaignId, campaignName, bundle);
	}

	private TrackingItemResponse assembleForList(MonitoringItemRow item, TargetRow target,
			Map<Long, CampaignRow> campaignsById, PostBundle bundle, LocalDate today) {
		String status = ItemStatus.derive(item, target, today);
		Long campaignId = item.campaignId();
		String campaignName = campaignId == null ? null
				: Optional.ofNullable(campaignsById.get(campaignId)).map(CampaignRow::name).orElse(null);
		campaignId = CampaignPairing.pair(campaignId, campaignName);
		return build(item, target, status, item.trackingDays(), campaignId, campaignName, bundle);
	}

	private TrackingItemResponse build(MonitoringItemRow item, TargetRow target, String status, int trackingDays,
			Long campaignId, String campaignName, PostBundle bundle) {
		String handle;
		String displayName;
		String profileImageUrl = null;
		String lastUploadedAt = null;
		Long followers = null;

		if (target != null) {
			String username = target.username();
			handle = username.toLowerCase(Locale.ROOT);
			ProfileMetaRow meta = bundle.profileMetaByUsername().get(username);
			displayName = meta != null && meta.displayName() != null ? meta.displayName() : handle;
			profileImageUrl = meta == null ? null : resolveImageUrl(meta);
			lastUploadedAt = meta == null || meta.lastUploadedAt() == null ? null : meta.lastUploadedAt().toString();
			followers = bundle.followersByUsername().get(username);
		} else if (MODE_ACCOUNT.equals(item.mode())) {
			// 등록 입력 그대로 정규화된 소문자 핸들(registration 단계에서 이미 정규화) — target 확정 전.
			handle = item.inputValue();
			displayName = handle;
		} else {
			// url 모드·target 미확정(collecting) — 계약: 서버가 아직 알 수 없으므로 빈 문자열 허용.
			handle = "";
			displayName = "";
		}

		TrackingItemResponse.Keywords keywords = MODE_ACCOUNT.equals(item.mode())
				? TrackingItemResponse.Keywords.from(readKeywordRule(item.keywords()))
				: null;

		TrackingItemResponse.TrackedPostResponse post = buildPost(item, target, bundle);
		OffsetDateTime nextCheckAt = nextCheckAt(status);

		return TrackingItemResponse.full(item.id(), item.mode(), status, handle, displayName, profileImageUrl,
				followers, lastUploadedAt, campaignId, campaignName, item.sourceUrl(), item.registeredOn(),
				trackingDays, keywords, post, nextCheckAt);
	}

	/**
	 * 프로필 이미지 URL 산지 — image_object_path(monitoring이 자체 아카이브한 OCI 오브젝트, 설계 스펙
	 * §3-1)가 있으면 그걸 우선 서빙하고, 없으면 원본 CDN URL로 폴백한다(기존 sanitizeImageUrl 가드는
	 * 폴백 경로에 그대로 유지). {@code /img/}는 was 엔드포인트가 아니라 celfit-front의 Vercel rewrite
	 * ({@code /img/:path*} 글롭)라서 프론트 변경이 필요 없다.
	 */
	private static String resolveImageUrl(ProfileMetaRow meta) {
		if (meta.imageObjectPath() != null) {
			return "/img/" + meta.imageObjectPath();
		}
		return sanitizeImageUrl(meta.profileImageUrl());
	}

	/**
	 * 썸네일 URL 산지(트랙 KK 확장 — resolveImageUrl과 동형) — image_object_path(monitoring이 자체
	 * 아카이브한 OCI 오브젝트)가 있으면 그걸 우선 서빙하고, 없으면 원본 CDN URL로 폴백한다(sanitizeImageUrl
	 * 가드는 폴백 경로에 그대로 유지). {@code /img/}는 was 엔드포인트가 아니라 celfit-front의 Vercel
	 * rewrite({@code /img/:path*} 글롭)라서 프론트 변경이 필요 없다.
	 */
	private static String resolveThumbnailUrl(PostMetaRow meta) {
		if (meta.imageObjectPath() != null) {
			return "/img/" + meta.imageObjectPath();
		}
		return sanitizeImageUrl(meta.thumbnailUrl());
	}

	/**
	 * 저장 측(monitoring ProfileMetaRepository·PostMetaRepository)이 무효 스킴을 걸러도 이미 DB에 박힌
	 * 값이 있을 수 있어 서빙 측에서 한 번 더 방어한다(이중 방어) — http(s)가 아니면 null로 강등.
	 */
	private static String sanitizeImageUrl(String profileImageUrl) {
		if (profileImageUrl == null) {
			return null;
		}
		String lower = profileImageUrl.toLowerCase(Locale.ROOT);
		return lower.startsWith("http://") || lower.startsWith("https://") ? profileImageUrl : null;
	}

	/** post는 target 확정 & tracked_short_code가 있을 때만 값(6.25 상태 불변식 표). */
	private TrackingItemResponse.TrackedPostResponse buildPost(MonitoringItemRow item, TargetRow target,
			PostBundle bundle) {
		if (target == null || target.trackedShortCode() == null) {
			return null;
		}
		String shortCode = target.trackedShortCode();
		PostMetaRow meta = bundle.postMetaByCode().get(shortCode);

		boolean isFeed = meta == null || !CONTENT_TYPE_REELS.equalsIgnoreCase(meta.contentType());
		String contentType = isFeed ? "feed" : "reels";
		String url = buildPostUrl(item, contentType, shortCode);
		String uploadedAt = meta != null && meta.uploadedAt() != null ? meta.uploadedAt().toString() : null;
		String caption = meta != null && meta.caption() != null ? meta.caption() : "";
		String thumbnailUrl = meta == null ? null : resolveThumbnailUrl(meta);

		List<String> matchedKeywords =
				MODE_URL.equals(item.mode()) ? List.of() : readMatchedKeywords(target.matchedKeywords());

		String hiddenAt = target.trackedHiddenAt() == null ? null
				: KstTimestamps.toKstDate(target.trackedHiddenAt()).toString();

		LocalDate snapshotFloor = snapshotFloor(item, target);
		List<TrackingItemResponse.SnapshotResponse> snapshots = bundle.snapshotsByCode()
				.getOrDefault(shortCode, List.of()).stream()
				.filter(s -> !s.capturedOn().isBefore(snapshotFloor))
				.map(s -> toSnapshotResponse(s, isFeed))
				.toList();

		List<TrackingItemResponse.PostCommentResponse> comments = bundle.commentsByCode()
				.getOrDefault(shortCode, List.of()).stream()
				.map(TrackingItemAssembler::toCommentResponse)
				.filter(Objects::nonNull)
				.toList();

		return new TrackingItemResponse.TrackedPostResponse(url, contentType, uploadedAt, caption, matchedKeywords,
				thumbnailUrl, hiddenAt, snapshots, comments);
	}

	/** url 모드는 등록 원문(sourceUrl)을 그대로, account 모드는 content_type 기반으로 shortcode를 조립한다(6.25). */
	private static String buildPostUrl(MonitoringItemRow item, String contentType, String shortCode) {
		if (MODE_URL.equals(item.mode()) && item.sourceUrl() != null) {
			return item.sourceUrl();
		}
		String segment = "reels".equals(contentType) ? "reel" : "p";
		return "https://www.instagram.com/" + segment + "/" + shortCode + "/";
	}

	/** 소급 금지(계약) — 스냅샷 하한은 account 모드면 tracked_since의 KST 날짜, 그 외(url)는 등록일. */
	private static LocalDate snapshotFloor(MonitoringItemRow item, TargetRow target) {
		if (MODE_ACCOUNT.equals(item.mode()) && target.trackedSince() != null) {
			return KstTimestamps.toKstDate(target.trackedSince());
		}
		return item.registeredOn();
	}

	// 패키지 가시성(private 아님) — 댓글·스냅샷 매핑 규칙을 DB 왕복 없이 단위 테스트하기 위한 테스트 심.
	static TrackingItemResponse.SnapshotResponse toSnapshotResponse(TrackedSnapshotRow s,
			boolean postLevelIsFeed) {
		// buildPost의 isFeed 계산과 방향을 통일: 불명·비정상 content_type은 "REELS가 아니면 feed"로 보수적으로
		// 접는다(0/거짓 지표 노출 방지가 우선 — REELS로 잘못 단정해 views·shares·reposts를 노출시키면 안 됨).
		boolean isFeed = s.contentType() == null ? postLevelIsFeed : !CONTENT_TYPE_REELS.equalsIgnoreCase(s.contentType());
		Long views = isFeed ? null : s.views();
		Long shares = isFeed ? null : s.shares();
		Long reposts = isFeed ? null : s.reposts();
		// 피드는 공유 자체가 미지원(null 강제)이라 "숨김" 신호도 접는다 — 미지원과 비공개는 다른 상태다.
		boolean sharesHidden = !isFeed && s.sharesHidden();
		return new TrackingItemResponse.SnapshotResponse(s.capturedOn().toString(), views, s.likes(),
				s.likesHidden(), s.comments(), s.saves(), shares, sharesHidden, reposts);
	}

	/** 필드 결손 댓글(author·body·likeCount·commentedAt 중 하나라도 null)은 통째로 제외(계약 PostComment). */
	static TrackingItemResponse.PostCommentResponse toCommentResponse(PostCommentRow row) {
		if (row.author() == null || row.body() == null || row.likeCount() == null || row.commentedAt() == null) {
			return null;
		}
		// 답글 선행 @핸들도 마스킹(2026-09-03, FE 피드백 #4-D) — author 마스킹이 본문으로 새지 않게.
		TrackingItemResponse.PostCommentResponse.Reply reply = row.ownerReplyText() == null ? null
				: new TrackingItemResponse.PostCommentResponse.Reply(AuthorMask.maskReply(row.ownerReplyText()));
		return new TrackingItemResponse.PostCommentResponse(row.id(), AuthorMask.mask(row.author()), row.body(),
				row.likeCount(), KstTimestamps.toKstIso(row.commentedAt()), reply);
	}

	private static OffsetDateTime nextCheckAt(String status) {
		if (ItemStatus.DETECTING.equals(status)) {
			return MonitoringExpiry.nextSweepAt();
		}
		if (ItemStatus.COLLECTING.equals(status)) {
			return OffsetDateTime.now(KstTimestamps.KST).plusMinutes(5);
		}
		return null;
	}

	/**
	 * 목록 조립용 번들 — 표시 부속(post_meta·profile_meta·팔로워)은 통합 조회 결과에서 뽑고, 행 수가
	 * 많은 스냅샷·댓글만 배치 왕복한다. 같은 username·shortCode를 공유하는 target이 여럿이면(계정+게시물
	 * 동시 추적 등) 값이 동일하므로 먼저 온 것을 유지한다(merge (a, b) -> a).
	 */
	private PostBundle buildListBundle(List<TargetDetailRow> details, Set<String> shortCodes,
			LocalDate lastCollectedDate) {
		Map<String, PostMetaRow> postMetaByCode = details.stream()
				.map(TargetDetailRow::toPostMetaRow).filter(Objects::nonNull)
				.collect(Collectors.toMap(PostMetaRow::shortCode, r -> r, (a, b) -> a));
		Map<String, ProfileMetaRow> profileMetaByUsername = details.stream()
				.map(TargetDetailRow::toProfileMetaRow).filter(Objects::nonNull)
				.collect(Collectors.toMap(ProfileMetaRow::username, r -> r, (a, b) -> a));
		// followers null(스냅샷 부재 또는 값 결손)은 맵 미포함 = 응답 null — 기존 findLatestProfileSnapshots
		// 미포함과 동일 의미(값 결손이 NPE였던 구 동작은 이 경로에선 null 강등으로 흡수).
		Map<String, Long> followersByUsername = details.stream()
				.filter(d -> d.followers() != null)
				.collect(Collectors.toMap(TargetDetailRow::username, TargetDetailRow::followers, (a, b) -> a));

		if (readRepository.isEmpty()) {
			return new PostBundle(postMetaByCode, Map.of(), Map.of(), profileMetaByUsername, followersByUsername);
		}
		MonitoringReadRepository repo = readRepository.get();
		Map<String, List<TrackedSnapshotRow>> snapshotsByCode = lastCollectedDate == null ? Map.of()
				: repo.findSnapshots(shortCodes, lastCollectedDate).stream()
						.collect(Collectors.groupingBy(TrackedSnapshotRow::shortCode));
		Map<String, List<PostCommentRow>> commentsByCode = repo.findComments(shortCodes, COMMENT_LIMIT).stream()
				.collect(Collectors.groupingBy(PostCommentRow::shortCode));
		return new PostBundle(postMetaByCode, snapshotsByCode, commentsByCode, profileMetaByUsername,
				followersByUsername);
	}

	private PostBundle fetchPostBundle(Set<String> shortCodes, Set<String> usernames, LocalDate lastCollectedDate) {
		if (readRepository.isEmpty()) {
			return new PostBundle(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
		}
		MonitoringReadRepository repo = readRepository.get();
		Map<String, PostMetaRow> postMetaByCode =
				repo.findPostMeta(shortCodes).stream().collect(Collectors.toMap(PostMetaRow::shortCode, r -> r));
		// lastCollectedDate가 null(성공한 배치가 없음)이면 워터마크 이전 데이터가 없다는 뜻 — 스냅샷은 항상 빈 배열(계약).
		Map<String, List<TrackedSnapshotRow>> snapshotsByCode = lastCollectedDate == null ? Map.of()
				: repo.findSnapshots(shortCodes, lastCollectedDate).stream()
						.collect(Collectors.groupingBy(TrackedSnapshotRow::shortCode));
		Map<String, List<PostCommentRow>> commentsByCode = repo.findComments(shortCodes, COMMENT_LIMIT).stream()
				.collect(Collectors.groupingBy(PostCommentRow::shortCode));
		Map<String, ProfileMetaRow> profileMetaByUsername =
				repo.findProfileMeta(usernames).stream().collect(Collectors.toMap(ProfileMetaRow::username, r -> r));
		Map<String, Long> followersByUsername = repo.findLatestProfileSnapshots(usernames).stream()
				.collect(Collectors.toMap(ProfileSnapshotBatchRow::username, ProfileSnapshotBatchRow::followers));
		return new PostBundle(postMetaByCode, snapshotsByCode, commentsByCode, profileMetaByUsername,
				followersByUsername);
	}

	/** 저장 키는 or지만 계약·모니터링 어휘는 any다(MonitoringRegistrationExecutor.readKeywordRule와 동일 변환). */
	private KeywordRule readKeywordRule(String keywordsJson) {
		// keywords가 NULL(또는 blank)인 레거시 개인 추적 아이템 방어 — 빈 규칙으로 취급.
		if (keywordsJson == null || keywordsJson.isBlank()) {
			return new KeywordRule(List.of(), List.of(), List.of());
		}
		Map<?, ?> map = objectMapper.readValue(keywordsJson, Map.class);
		return new KeywordRule(castList(map.get("and")), castList(map.get("or")), castList(map.get("exclude")));
	}

	@SuppressWarnings("unchecked")
	private List<String> readMatchedKeywords(String json) {
		if (json == null) {
			return List.of();
		}
		List<String> list = (List<String>) objectMapper.readValue(json, List.class);
		return list == null ? List.of() : list;
	}

	@SuppressWarnings("unchecked")
	private static List<String> castList(Object raw) {
		return raw == null ? List.of() : (List<String>) raw;
	}

	/** GET /v1/monitoring/items(6.26) 응답 조립 결과 — meta(lastCollectedAt·today)까지 함께 반환. */
	public record AssembledList(List<TrackingItemResponse> items, OffsetDateTime lastCollectedAt, LocalDate today) {
	}

	/** 목록·단건 조립 공용 배치 조회 결과 — 키는 shortCode/username(원본 그대로, 소문자 변환 전). */
	private record PostBundle(Map<String, PostMetaRow> postMetaByCode,
			Map<String, List<TrackedSnapshotRow>> snapshotsByCode, Map<String, List<PostCommentRow>> commentsByCode,
			Map<String, ProfileMetaRow> profileMetaByUsername, Map<String, Long> followersByUsername) {
	}
}
