package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.AuthorRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandCommentRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandPostMetaRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandSnapshotRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandTaggedPostRow;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * BrandPost 조립(스펙 §6-1) — 두 산지를 한 셰이프로 합친다.
 *
 * <ul>
 *   <li><b>tagged</b>: 브랜드 전용 테이블(brand_tagged_post 외 4종)을 shortcode 묶음으로 배치 조회.
 *       윈도우는 KST 오늘−90일 이후 최신순 105건 — 상한·컷은 여기(상위 계층)가 정한다.</li>
 *   <li><b>direct</b>: app.brand_direct_posts 매핑 → 레거시 {@link TrackingItemAssembler} 결과 재사용.
 *       직접 등록분의 상태·스냅샷·댓글 정본은 끝까지 레거시 경로다(브랜드 스윕은 이 게시물을 모른다).</li>
 * </ul>
 *
 * <p>병합은 shortcode 키, direct 우선(명시 등록 보존)이되 tagged의 {@code is_paid_partnership}
 * 관측이 있으면 협찬 판정만 그 값으로 승격한다 — 브랜드 스윕만 볼 수 있는 신호라 버리면 정보 손실이다.
 *
 * <p>필터·정렬은 여기서 하지 않는다(컨트롤러 몫) — 이 클래스는 "전량"을 업로드 최신순으로 돌려주고,
 * Task 9(성과 대시보드)도 같은 전량을 소비한다.
 *
 * <p>조립 규칙(taggedPost·directPost·snapshotOf·mergeByShortcode)은 전부 정적 순수 함수라
 * DB 없이 단위 테스트한다. 인스턴스 메서드는 배치 조회 배선만 담당한다.
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class BrandPostAssembler {

	static final String SOURCE_TAGGED = "tagged";
	static final String SOURCE_DIRECT = "direct";

	private static final Logger log = LoggerFactory.getLogger(BrandPostAssembler.class);

	/** 표시 윈도우 90일 · 상한 105건(스펙 §6-1 — 90일치 + 여유분). */
	private static final int WINDOW_DAYS = 90;
	private static final int TAGGED_LIMIT = 105;
	/** 댓글 서빙 상한 — monitoring 수집 상한(3페이지 45건)과 같은 수로 맞춘다(레거시 COMMENT_LIMIT 동형). */
	private static final int COMMENT_LIMIT = 45;

	private static final String CONTENT_TYPE_REELS = "REELS";
	private static final String REELS = "reels";
	private static final String FEED = "feed";
	private static final String PROFILE_URL_PREFIX = "https://www.instagram.com/";
	/** 목록에 실리는 tagged는 전부 윈도우 안이라 항상 tracking이다(ended는 목록 밖 보존 — 스펙 §6-1). */
	private static final String TRACKING = "tracking";

	private final BrandReadRepository brandReadRepository;
	private final BrandDirectPostRepository directPostRepository;
	private final TrackingItemAssembler trackingItemAssembler;

	public BrandPostAssembler(BrandReadRepository brandReadRepository,
			BrandDirectPostRepository directPostRepository, TrackingItemAssembler trackingItemAssembler) {
		this.brandReadRepository = brandReadRepository;
		this.directPostRepository = directPostRepository;
		this.trackingItemAssembler = trackingItemAssembler;
	}

	/**
	 * 브랜드 1계정의 게시물 전량(필터 전) — 업로드 최신순, takenAt 미상은 마지막.
	 * 소유권 검증은 호출부 책임이다(여기 오는 account는 이미 내 브랜드여야 한다).
	 */
	public List<BrandPostResponse> assembleForBrand(long userId, BrandAccountRow account) {
		List<BrandPostResponse> tagged = assembleTagged(account);
		List<BrandPostResponse> direct = assembleDirect(userId, account.id());
		return mergeByShortcode(direct, tagged);
	}

	// ---------- tagged ----------

	private List<BrandPostResponse> assembleTagged(BrandAccountRow account) {
		// 컷은 KST 달력일 기준이다 — 인스턴트에서 90일을 빼면 요청 시각에 따라 경계일이 들쭉날쭉해진다.
		OffsetDateTime cutoff = LocalDate.now(KstTimestamps.KST).minusDays(WINDOW_DAYS)
				.atStartOfDay(KstTimestamps.KST).toOffsetDateTime();
		List<BrandTaggedPostRow> posts =
				brandReadRepository.findTaggedPostsInWindow(account.id(), cutoff, TAGGED_LIMIT);
		if (posts.isEmpty()) {
			return List.of();
		}

		Set<String> codes = posts.stream().map(BrandTaggedPostRow::shortCode)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, BrandPostMetaRow> metaByCode = brandReadRepository.findPostMeta(codes).stream()
				.collect(Collectors.toMap(BrandPostMetaRow::shortCode, Function.identity(), (a, b) -> a));
		Map<String, List<BrandSnapshotRow>> snapshotsByCode = brandReadRepository.findSnapshots(codes).stream()
				.collect(Collectors.groupingBy(BrandSnapshotRow::shortCode));
		Map<String, List<BrandCommentRow>> commentsByCode =
				brandReadRepository.findComments(codes, COMMENT_LIMIT).stream()
						.collect(Collectors.groupingBy(BrandCommentRow::shortCode));
		Map<String, AuthorRow> authorsByPost = resolveAuthors(posts);

		return posts.stream()
				.map(p -> taggedPost(account.id(), p, metaByCode.get(p.shortCode()), authorsByPost.get(p.shortCode()),
						snapshotsByCode.getOrDefault(p.shortCode(), List.of()),
						commentsByCode.getOrDefault(p.shortCode(), List.of()), account.lastSweptAt()))
				.toList();
	}

	/**
	 * 게시자 프로필 해석 — 기본은 ig_user_id, 못 찾은 건만 username으로 한 번 더 찾는다(2차 SQL은
	 * 전부 해석되면 아예 돌지 않는다). 열거 셰이프에 따라 author_ig_user_id가 비는 행이 있어
	 * 폴백 경로가 필요하다({@code findAuthorsByUsername}은 계정당 최신 1행만 준다).
	 */
	private Map<String, AuthorRow> resolveAuthors(List<BrandTaggedPostRow> posts) {
		Set<String> igUserIds = posts.stream().map(BrandTaggedPostRow::authorIgUserId)
				.filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, AuthorRow> byIgUserId = brandReadRepository.findAuthors(igUserIds).stream()
				.collect(Collectors.toMap(AuthorRow::igUserId, Function.identity(), (a, b) -> a));

		Map<String, AuthorRow> byPost = new LinkedHashMap<>();
		Set<String> pendingUsernames = new LinkedHashSet<>();
		for (BrandTaggedPostRow post : posts) {
			AuthorRow row = post.authorIgUserId() == null ? null : byIgUserId.get(post.authorIgUserId());
			if (row != null) {
				byPost.put(post.shortCode(), row);
			} else if (post.authorUsername() != null) {
				pendingUsernames.add(post.authorUsername());
			}
		}
		if (pendingUsernames.isEmpty()) {
			return byPost;
		}

		Map<String, AuthorRow> byUsername = brandReadRepository.findAuthorsByUsername(pendingUsernames).stream()
				.collect(Collectors.toMap(AuthorRow::username, Function.identity(), (a, b) -> a));
		for (BrandTaggedPostRow post : posts) {
			if (!byPost.containsKey(post.shortCode()) && post.authorUsername() != null) {
				AuthorRow row = byUsername.get(post.authorUsername());
				if (row != null) {
					byPost.put(post.shortCode(), row);
				}
			}
		}
		return byPost;
	}

	/**
	 * tagged 1건 조립 — 지표·표시값의 산지가 전부 다르다: 게시물 메타는 brand_post_meta,
	 * 게시자는 author_profile(부재 시 열거 관측값 폴백), 갱신 시각은 계정의 마지막 스윕이다.
	 *
	 * <p>{@code commentsCollectedCount}는 조회된 댓글 행 수다 — {@code brand_tagged_post.
	 * comments_collected_count} 컬럼을 쓰지 않는다. 그 컬럼은 "마지막 수집 시점의 열거 comment_count"
	 * 즉 재수집 게이트의 워터마크라(monitoring BrandCollectService), 저장된 댓글 수와 다른 값이다.
	 */
	static BrandPostResponse taggedPost(long brandId, BrandTaggedPostRow post, BrandPostMetaRow meta,
			AuthorRow author, List<BrandSnapshotRow> snapshotRows, List<BrandCommentRow> commentRows,
			OffsetDateTime lastSweptAt) {
		String contentType = contentTypeOf(meta == null ? null : meta.contentType());
		List<TrackingItemResponse.SnapshotResponse> snapshots =
				snapshotRows.stream().map(BrandPostAssembler::snapshotOf).toList();
		List<TrackingItemResponse.PostCommentResponse> comments = commentRows.stream()
				.map(BrandPostAssembler::commentOf).filter(Objects::nonNull).toList();
		String username = author != null ? author.username() : post.authorUsername();
		String firstSeenAt = KstTimestamps.toKstIso(post.firstSeenAt());

		return new BrandPostResponse(
				post.shortCode(),
				String.valueOf(brandId),
				SOURCE_TAGGED,
				postUrl(contentType, post.shortCode()),
				post.shortCode(),
				contentType,
				KstTimestamps.toKstIso(post.takenAt()),
				meta == null ? null : meta.caption(),
				meta == null ? null : sanitizeImageUrl(meta.thumbnailUrl()),
				meta == null ? null : meta.videoUrl(),
				meta == null ? null : meta.videoDuration(),
				username == null ? null : PROFILE_URL_PREFIX + username + "/",
				username,
				author == null ? null : author.fullName(),
				author == null ? null : sanitizeImageUrl(author.profilePicUrl()),
				author != null && Boolean.TRUE.equals(author.isVerified()),
				author == null ? null : author.followers(),
				BrandSponsorshipClassifier.classify(meta == null ? null : meta.isPaidPartnership(),
						meta == null ? null : meta.caption()),
				meta == null ? null : meta.isPaidPartnership(),
				TRACKING,
				firstSeenAt,
				// 윈도우 안 게시물만 실리므로 종료 시각이 존재할 수 없다(윈도우를 벗어나면 목록에서 빠진다).
				null,
				latestOf(snapshots),
				snapshots,
				commentsTotal(snapshots),
				commentsHidden(snapshots),
				comments.size(),
				comments,
				// 브랜드 태그 게시물은 캠페인에 연결되지 않는다(캠페인 연결은 레거시 아이템의 속성).
				List.of(),
				firstSeenAt,
				KstTimestamps.toKstIso(lastSweptAt));
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

	// ---------- direct ----------

	private List<BrandPostResponse> assembleDirect(long userId, long brandId) {
		List<BrandDirectPostRepository.Row> rows = directPostRepository.findByUser(userId).stream()
				.filter(r -> r.brandId() == brandId)
				.toList();
		if (rows.isEmpty()) {
			return List.of();
		}

		// 레거시 목록은 유저 전량을 한 번에 조립한다 — 매핑 건수만큼 단건 조립을 반복하면 N+1이다.
		TrackingItemAssembler.AssembledList assembled = trackingItemAssembler.assembleList(userId);
		Map<String, TrackingItemResponse> itemsById = assembled.items().stream()
				.collect(Collectors.toMap(TrackingItemResponse::id, Function.identity(), (a, b) -> a));

		List<BrandPostResponse> direct = new ArrayList<>(rows.size());
		for (BrandDirectPostRepository.Row row : rows) {
			TrackingItemResponse item = itemsById.get(String.valueOf(row.monitoringItemId()));
			if (item == null) {
				// 매핑이 가리키는 레거시 행이 사라진 경우(수동 정리 등) — 목록을 죽이지 않고 건너뛴다.
				log.warn("직접 등록 매핑의 레거시 아이템 부재 — 건너뜀 userId={}, shortCode={}, itemId={}",
						userId, row.shortCode(), row.monitoringItemId());
				continue;
			}
			direct.add(directPost(brandId, row.shortCode(), item, assembled.lastCollectedAt()));
		}
		return direct;
	}

	/**
	 * direct 1건 변환 — 레거시 TrackingItem을 BrandPost 셰이프로 옮긴다. 스냅샷·댓글은 이미 레거시
	 * 어셈블러가 계약대로 만든 값이라 그대로 통과시킨다(셰이프 동형).
	 *
	 * <p>shortcode는 반드시 매핑 행 값이다 — 레거시 응답엔 shortcode 필드가 없고(post.url만 있다)
	 * URL 파싱은 등록 원문 형태에 좌우된다.
	 *
	 * @param legacyLastCollectedAt 레거시 마지막 성공 스윕 시각 — direct 게시물의 갱신 시각은 브랜드
	 *                              스윕이 아니라 이쪽이다(브랜드 스윕은 이 게시물을 수집하지 않는다).
	 */
	static BrandPostResponse directPost(long brandId, String shortCode, TrackingItemResponse item,
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
				// 레거시 수집엔 영상 원본 URL·길이가 없다(브랜드 전용 필드).
				null,
				null,
				username == null ? null : PROFILE_URL_PREFIX + username + "/",
				username,
				item.displayName() == null || item.displayName().isEmpty() ? null : item.displayName(),
				item.profileImageUrl(),
				// 레거시 프로필엔 인증 배지 관측이 없다 — 거짓 배지를 띄우지 않기 위해 false 고정.
				false,
				item.followers(),
				// 레거시엔 is_paid_partnership 관측이 없어 캡션 키워드만으로 판정한다(같은 shortcode의
				// tagged 관측이 있으면 mergeByShortcode가 승격시킨다).
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
				KstTimestamps.toKstIso(legacyLastCollectedAt));
	}

	// ---------- 병합 ----------

	/**
	 * shortcode 병합 — direct 우선(명시 등록 보존)이되, 겹치는 tagged에 유료협찬 관측이 있으면
	 * 협찬 판정만 그 값으로 승격한다. 결과는 업로드 최신순(takenAt 미상은 마지막, 동률은 shortcode).
	 */
	static List<BrandPostResponse> mergeByShortcode(List<BrandPostResponse> direct,
			List<BrandPostResponse> tagged) {
		Map<String, BrandPostResponse> byCode = new LinkedHashMap<>();
		for (BrandPostResponse post : direct) {
			byCode.put(post.shortcode(), post);
		}
		for (BrandPostResponse post : tagged) {
			byCode.merge(post.shortcode(), post, (existing, taggedPost) -> promoteSponsorship(existing, taggedPost));
		}
		return byCode.values().stream()
				.sorted(Comparator.comparing(BrandPostAssembler::uploadedOn,
								Comparator.nullsLast(Comparator.reverseOrder()))
						.thenComparing(BrandPostResponse::shortcode))
				.toList();
	}

	/** direct 본체는 그대로 두고 tagged의 유료협찬 관측만 얹는다(관측이 없으면 무변경). */
	private static BrandPostResponse promoteSponsorship(BrandPostResponse direct, BrandPostResponse tagged) {
		if (tagged.isPaidPartnership() == null) {
			return direct;
		}
		String caption = direct.caption() != null ? direct.caption() : tagged.caption();
		return direct.withSponsorship(
				BrandSponsorshipClassifier.classify(tagged.isPaidPartnership(), caption),
				tagged.isPaidPartnership());
	}

	/**
	 * 업로드 날짜(KST) — takenAt은 산지에 따라 타임스탬프(tagged)와 날짜(direct, 레거시 uploaded_at이
	 * date 컬럼)가 섞여 들어와서 앞 10자만 본다. 정렬·업로드 기간 필터의 공용 키다.
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
}
