package com.celfit.was.v1.brandmonitoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 브랜드 태그 게시물을 인플루언서(작성자) 단위로 접는 집계 — celfit-front
 * {@code src/lib/monitoring/brand-influencers.ts} 1:1 이식(2026-08-27) — 규칙 변경은 FE와 동시에.
 *
 * <p>FE가 정본이다. 화면이 이미 이 규칙으로 수를 보여주고 있어서, 서버가 다르게 접으면 같은
 * 화면에서 두 벌의 수가 생긴다. 함수마다 대응하는 FE 함수명을 주석에 남겨 대조 근거를 고정한다.
 *
 * <p>서버 한정 차이는 셋뿐이다(FE 타입이 non-null이라 분기가 없던 자리):
 * <ul>
 * <li>{@code username}이 null·공백인 게시물은 집계에서 제외한다 — 사람 단위로 접을 키가 없다.</li>
 * <li>{@code fullName}·{@code takenAtKst}가 null일 수 있어 비교·일치 판정에 null 가드를 둔다.</li>
 * <li>정렬 tie-break는 {@code localeCompare} 대신 {@link String#compareTo}(코드포인트 순)다 —
 * 계정명은 ASCII라 실질적으로 같고, 로케일에 따라 순서가 흔들리지 않는 쪽을 택했다.</li>
 * </ul>
 */
public final class BrandInfluencerAggregator {

	/** 정렬 토큰 7종 — API 계약(FE {@code BrandInfluencerSort}의 {@code avgViews}는 {@code avg_views}). */
	public static final String SORT_POSTS = "posts";
	public static final String SORT_AVG_VIEWS = "avg_views";
	public static final String SORT_VIEWS = "views";
	public static final String SORT_LIKES = "likes";
	public static final String SORT_ENGAGEMENT = "engagement";
	public static final String SORT_FOLLOWERS = "followers";
	public static final String SORT_RECENT = "recent";

	/** 정렬 토큰 허용 목록 — 컨트롤러의 파라미터 검증이 이걸 본다. */
	public static final List<String> SORT_KEYS = List.of(SORT_POSTS, SORT_AVG_VIEWS, SORT_VIEWS,
			SORT_LIKES, SORT_ENGAGEMENT, SORT_FOLLOWERS, SORT_RECENT);

	private static final String PROFILE_URL_PREFIX = "https://www.instagram.com/";
	private static final String FILTER_ALL = "all";
	private static final String FILTER_SPONSORED = "sponsored";

	private static final Comparator<BrandInfluencerResponse> POST_COUNT_DESC = Comparator
			.<BrandInfluencerResponse>comparingLong(BrandInfluencerResponse::postCount).reversed();
	private static final Comparator<BrandInfluencerResponse> LIKES_DESC = Comparator
			.<BrandInfluencerResponse>comparingLong(BrandInfluencerResponse::likes).reversed();
	private static final Comparator<BrandInfluencerResponse> VIEWS_DESC = Comparator
			.<BrandInfluencerResponse>comparingLong(BrandInfluencerResponse::views).reversed();
	private static final Comparator<BrandInfluencerResponse> AVG_VIEWS_DESC = Comparator
			.<BrandInfluencerResponse>comparingLong(BrandInfluencerAggregator::averageViews).reversed();
	private static final Comparator<BrandInfluencerResponse> USERNAME_ASC = Comparator
			.comparing(BrandInfluencerResponse::username);

	private BrandInfluencerAggregator() {
	}

	/**
	 * 집계 입력 1행(게시물 단위) — 컨트롤러가 {@code PostRef} + 최신 지표를 접어 만든다.
	 *
	 * @param likesHidden 게시자가 좋아요를 숨겼는지. true면 {@code likes} 값이 있어도 합산하지 않는다.
	 * @param influencerId 2026-09-03 — {@code username}의 발굴 존재 판정 결과({@link
	 *                     BrandPostAssembler.PostRef#influencerId}에서 그대로 옮겨 온다). username
	 *                     단위 값이라 이 작성자의 모든 게시물에서 항상 같다.
	 */
	public record InfluencerPost(String shortcode, String username, String fullName,
			String profilePicUrl, Long followers, String takenAtKst, boolean sponsored,
			Long views, Long likes, boolean likesHidden, Long comments, String influencerId) {
	}

	/**
	 * shortcode 중복 제거 — FE {@code mergeBrandPosts}. 먼저 온 것을 남긴다.
	 *
	 * <p>한 게시물이 두 브랜드 계정을 태그하면 계정별 목록에 모두 들어온다. 중복을 두면 그
	 * 게시물의 성과가 두 번 접힌다.
	 */
	public static List<InfluencerPost> dedupeByShortcode(List<InfluencerPost> posts) {
		Set<String> seen = new LinkedHashSet<>();
		List<InfluencerPost> merged = new ArrayList<>();
		for (InfluencerPost post : posts) {
			if (!seen.add(post.shortcode())) {
				continue;
			}
			merged.add(post);
		}
		return List.copyOf(merged);
	}

	/**
	 * 작성자별 누적 — FE {@code summarizeBrandInfluencers}. 반환 순서도 FE와 같다(게시물 수 →
	 * 좋아요 → 조회수 → 계정명).
	 *
	 * <p>좋아요는 <b>셀 수 있었던 게시물만</b> 더한다({@code !likesHidden && likes != null}).
	 * 숨김을 0으로 더하면 "좋아요를 못 받은 사람"으로 둔갑한다. 조회수·댓글의 null은 0 기여다
	 * (피드 게시물은 조회수가 구조적으로 null).
	 */
	public static List<BrandInfluencerResponse> summarize(List<InfluencerPost> posts) {
		Map<String, Accumulator> byUsername = new LinkedHashMap<>();
		for (InfluencerPost post : posts) {
			// 서버 한정 가드 — 작성자를 모르면 사람 단위로 접을 키가 없다(FE는 항상 문자열).
			if (post.username() == null || post.username().isBlank()) {
				continue;
			}
			boolean likesKnown = !post.likesHidden() && post.likes() != null;
			Accumulator acc = byUsername.get(post.username());
			if (acc == null) {
				acc = new Accumulator(post.username());
				// influencerId는 username으로 결정되는 값이라(발굴 존재 판정) 최초 생성 시 1회만
				// 설정한다 — followers·fullName과 달리 "최근 게시물 값으로 갱신"할 이유가 없다.
				acc.influencerId = post.influencerId();
				acc.fullName = post.fullName();
				acc.profilePicUrl = post.profilePicUrl();
				acc.followers = post.followers();
				acc.latestPostAt = post.takenAtKst();
				byUsername.put(post.username(), acc);
			} else if (isNewer(post.takenAtKst(), acc.latestPostAt)) {
				// 팔로워는 합치면 안 되는 값이다. 게시물마다 수집 시점이 달라 가장 최근 값을 쓴다.
				acc.fullName = post.fullName();
				acc.profilePicUrl = post.profilePicUrl();
				if (post.followers() != null) {
					// 최근 게시물이라도 미수집이면 직전 값을 지키지 않을 이유가 없다(FE와 동일한 null 가드).
					acc.followers = post.followers();
				}
				acc.latestPostAt = post.takenAtKst();
			}
			acc.views += zeroIfNull(post.views());
			acc.likes += likesKnown ? zeroIfNull(post.likes()) : 0L;
			acc.comments += zeroIfNull(post.comments());
			acc.postCount++;
			acc.sponsoredCount += post.sponsored() ? 1L : 0L;
			acc.likesKnownCount += likesKnown ? 1L : 0L;
		}

		List<BrandInfluencerResponse> rows = new ArrayList<>(byUsername.size());
		for (Accumulator acc : byUsername.values()) {
			rows.add(acc.toResponse());
		}
		// FE summarizeBrandInfluencers 말미의 기본 정렬 — sort()가 다시 세우더라도 순서를 고정해 둔다.
		rows.sort(POST_COUNT_DESC.thenComparing(LIKES_DESC).thenComparing(VIEWS_DESC)
				.thenComparing(USERNAME_ASC));
		return List.copyOf(rows);
	}

	/** 좋아요를 한 건도 못 센 사람 — FE {@code isLikesUnavailable}. 합계 0과 구분해야 한다. */
	static boolean likesUnavailable(BrandInfluencerResponse r) {
		return r.likesKnownCount() == 0L && r.postCount() > 0L;
	}

	/**
	 * 게시물 한 건당 평균 조회수 — FE {@code averageViews}. {@code Math.round}는 JS와 Java 모두
	 * 0.5를 위로 올린다(양수만 다루므로 동치).
	 */
	static long averageViews(BrandInfluencerResponse r) {
		if (r.postCount() <= 0L) {
			return 0L;
		}
		return Math.round((double) r.views() / r.postCount());
	}

	/**
	 * 게시물 한 건당 평균 참여율(%) — FE {@code influencerEngagementRate}.
	 * {@code (좋아요 + 댓글) / (팔로워 × 게시물 수) × 100}.
	 *
	 * <p>팔로워를 모르거나 0이면, 게시물이 없으면, 좋아요를 한 건도 못 셌으면 계산하지 않는다(null).
	 * 0으로 두면 무한대가 되고, 좋아요 없이 계산하면 실제보다 몇 배 낮은 값이 정상값처럼 보인다.
	 */
	static Double engagementRate(BrandInfluencerResponse r) {
		if (r.followers() == null || r.followers() <= 0L) {
			return null;
		}
		if (r.postCount() <= 0L) {
			return null;
		}
		if (likesUnavailable(r)) {
			return null;
		}
		return ((double) (r.likes() + r.comments()) / ((double) r.followers() * r.postCount())) * 100d;
	}

	/**
	 * 정렬 — FE {@code sortBrandInfluencers}. 7종 모두 최종 tie-break는 계정명 오름차순이라
	 * 전순서다(페이지 경계에서 중복·누락이 없다). 알 수 없는 토큰은 FE의 {@code default}와 같이
	 * {@code posts}로 접는다(토큰 검증은 컨트롤러 몫).
	 */
	public static List<BrandInfluencerResponse> sort(List<BrandInfluencerResponse> list,
			String sortKey) {
		Comparator<BrandInfluencerResponse> primary = switch (sortKey == null ? SORT_POSTS : sortKey) {
			case SORT_VIEWS -> VIEWS_DESC;
			case SORT_AVG_VIEWS -> AVG_VIEWS_DESC;
			// 좋아요를 못 센 사람은 0이 아니라 "모름"이라 뒤로 보낸다.
			case SORT_LIKES -> (a, b) -> nullsLastDesc(likesUnavailable(a) ? null : (double) a.likes(),
					likesUnavailable(b) ? null : (double) b.likes());
			case SORT_ENGAGEMENT -> (a, b) -> nullsLastDesc(engagementRate(a), engagementRate(b));
			case SORT_FOLLOWERS -> (a, b) -> nullsLastDesc(
					a.followers() == null ? null : (double) a.followers(),
					b.followers() == null ? null : (double) b.followers());
			case SORT_RECENT -> (a, b) -> compareTextDesc(a.latestPostAt(), b.latestPostAt());
			default -> POST_COUNT_DESC.thenComparing(LIKES_DESC);
		};
		List<BrandInfluencerResponse> sorted = new ArrayList<>(list);
		sorted.sort(primary.thenComparing(USERNAME_ASC));
		return List.copyOf(sorted);
	}

	/**
	 * 계정명·닉네임 부분 일치(대소문자 무시) — FE {@code filterBrandInfluencers}. 빈 검색어는 필터
	 * 없음(전부 통과). 입력은 이미 접힌 값을 기대하지만 멱등하게 다시 trim·소문자로 접는다.
	 */
	public static boolean matchesKeyword(BrandInfluencerResponse r, String keywordLower) {
		if (keywordLower == null || keywordLower.isBlank()) {
			return true;
		}
		String keyword = keywordLower.trim().toLowerCase(Locale.ROOT);
		return (r.username() != null && r.username().toLowerCase(Locale.ROOT).contains(keyword))
				// fullName은 서버에서 null일 수 있다 — 그때는 계정명만 본다(FE는 항상 문자열).
				|| (r.fullName() != null && r.fullName().toLowerCase(Locale.ROOT).contains(keyword));
	}

	/**
	 * 팔로워 구간 — FE {@code matchesFollowerRange}. 밴드 null(=all)은 필터 없음.
	 * 경계·미상 규칙은 게시물 목록과 같은 판정을 재사용한다({@link V1BrandPostsController}).
	 */
	public static boolean matchesFollower(BrandInfluencerResponse r,
			V1BrandPostsController.FollowerBand band) {
		return band == null || V1BrandPostsController.matchesFollower(r.followers(), band);
	}

	/**
	 * 협찬 이력 유무 — FE {@code matchesSponsorship}. 횟수가 아니라 "받아본 적이 있는가"로 거른다.
	 * 미지정·{@code all}은 필터 없음, {@code sponsored}는 1건 이상, 그 밖(={@code organic})은 0건.
	 */
	public static boolean matchesSponsorship(BrandInfluencerResponse r, String filter) {
		if (filter == null || filter.isBlank() || FILTER_ALL.equals(filter)) {
			return true;
		}
		return FILTER_SPONSORED.equals(filter) ? r.sponsoredCount() > 0L : r.sponsoredCount() == 0L;
	}

	// ---------- 내부 ----------

	/** 값이 없는 사람은 항상 뒤로 — FE {@code nullsLast}. 미수집을 0으로 취급하면 "가장 낮은 사람"이 된다. */
	private static int nullsLastDesc(Double a, Double b) {
		if (a == null && b == null) {
			return 0;
		}
		if (a == null) {
			return 1;
		}
		if (b == null) {
			return -1;
		}
		return Double.compare(b, a);
	}

	/** 문자열 내림차순 + null 뒤로 — FE는 {@code localeCompare}지만 ISO 시각이라 사전순이 곧 시간순이다. */
	private static int compareTextDesc(String a, String b) {
		if (a == null && b == null) {
			return 0;
		}
		if (a == null) {
			return 1;
		}
		if (b == null) {
			return -1;
		}
		return b.compareTo(a);
	}

	/**
	 * 더 최근 게시물인지 — FE {@code post.takenAtKst > current.latestPostAtKst}(ISO 문자열 비교).
	 * 서버는 시각이 null일 수 있어, null은 "더 최근이 아님"으로 두고 값이 있는 쪽을 최신으로 본다.
	 */
	private static boolean isNewer(String candidate, String current) {
		if (candidate == null) {
			return false;
		}
		if (current == null) {
			return true;
		}
		return candidate.compareTo(current) > 0;
	}

	private static long zeroIfNull(Long value) {
		return value == null ? 0L : value;
	}

	/** FE가 스프레드로 새 객체를 만드는 자리 — Java는 가변 누적기 한 벌로 접고 마지막에 record로 굳힌다. */
	private static final class Accumulator {
		private final String username;
		private String influencerId;
		private String fullName;
		private String profilePicUrl;
		private Long followers;
		private long views;
		private long likes;
		private long comments;
		private long postCount;
		private long sponsoredCount;
		private long likesKnownCount;
		private String latestPostAt;

		private Accumulator(String username) {
			this.username = username;
		}

		private BrandInfluencerResponse toResponse() {
			return new BrandInfluencerResponse(username, fullName, profilePicUrl,
					PROFILE_URL_PREFIX + username + "/", followers, postCount, sponsoredCount, views,
					likes, comments, likesKnownCount, latestPostAt, influencerId);
		}
	}
}
