package com.celfit.instagram.source.self;

import com.celfit.instagram.source.AuthorInfo;
import com.celfit.instagram.source.ClipCounts;
import com.celfit.instagram.source.CommentsFetch;
import com.celfit.instagram.source.HashtagPage;
import com.celfit.instagram.source.InstagramSource;
import com.celfit.instagram.source.MediaRef;
import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.ProfileInfo;
import com.celfit.instagram.source.TaggedPage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 자체크롤 백엔드 — 표면 fetcher들을 InstagramSource 계약으로 정규화(스펙 §4). 자체 가능 경로만
 * 구현하고, 하드게이트 3종(태그드·해시태그 발견, by-id 작성자)과 프리미엄·clip 경로는
 * UnsupportedOperationException → FailoverInstagramSource가 Hiker로 라우팅한다. 표면별 서킷이
 * 열린 표면은 곧장 SelfCrawlException(OTHER)으로 폴백을 유도하고, run()은 SelfRetry로 회복가능
 * 실패(401·전송·429)를 새 IP로 재시도한 뒤 성공 시 서킷을 리셋, 소진·비회복 시 서킷 블록을
 * 기록하고 전파한다.
 *
 * <p>최근 게시물 정본은 feed/user REST(pk 입력)다 — wpi(web_profile_info)는 대형 공개계정 ~40%에서
 * 계정별 STRUCTURAL_400(IG 버그)에 항상 걸려 자체크롤이 매번 실패하는데, feed/user는 다른
 * 엔드포인트라 이 400을 피한다. pk가 없으면(userId null/blank) feed/user 경로가 불가하므로
 * fetcher를 부르지 않고 즉시 Hiker로 폴백한다.
 */
public class SelfCrawlBackend implements InstagramSource {

	private final EmbedPostFetcher embed;
	private final WpiProfileFetcher wpi;
	private final OgProfileFetcher og;
	private final FeedUserPostsFetcher feedUser;
	private final DirectCommentFetcher comments;
	private final SurfaceCircuitBreaker circuit;
	private final SelfRetry retry;
	// 프로필 표면 런타임 토글("og"/"wpi") — 매 콜 재평가라 app_setting 전환이 즉시 반영된다.
	private final Supplier<String> profileSurface;

	public SelfCrawlBackend(EmbedPostFetcher embed, WpiProfileFetcher wpi, OgProfileFetcher og,
			FeedUserPostsFetcher feedUser, DirectCommentFetcher comments,
			SurfaceCircuitBreaker circuit, SelfRetry retry, Supplier<String> profileSurface) {
		this.embed = embed;
		this.wpi = wpi;
		this.og = og;
		this.feedUser = feedUser;
		this.comments = comments;
		this.circuit = circuit;
		this.retry = retry;
		this.profileSurface = profileSurface;
	}

	@Override
	public PostInfo fetchPost(String shortCode) {
		return run("embed", () -> embed.fetch(shortCode));
	}

	@Override
	public ProfileInfo fetchProfile(String username) {
		if ("og".equals(profileSurface.get())) {
			return run("og", () -> og.fetchProfile(username));
		}
		return run("wpi", () -> wpi.fetchProfile(username));
	}

	@Override
	public List<PostInfo> fetchRecentPosts(String username, String userId, int pages) {
		if (userId == null || userId.isBlank()) {
			// feed/user는 pk가 입력 — pk가 없으면 자체 경로 불가, Hiker로 폴백한다.
			throw new SelfCrawlException(SelfErrorClass.OTHER, "feed/user pk 없음 — Hiker 폴백: " + username);
		}
		// 최근 게시물 소스 = feed/user REST(pk, page1). wpi STRUCTURAL_400 회피·영상 조회수 내장.
		return run("feed", () -> feedUser.fetchRecentPosts(username, userId));
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages) {
		return run("comment", () -> comments.fetch(shortCode, postUsername, pages));
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages,
			Set<String> knownCommentIds) {
		return fetchComments(shortCode, postUsername, pages);
	}

	@Override
	public AuthorInfo fetchAuthorProfile(String userId) {
		throw new UnsupportedOperationException("자체 미지원(하드게이트 by-id 작성자) — Hiker");
	}

	@Override
	public TaggedPage fetchTaggedPage(String userId, String pageId) {
		throw new UnsupportedOperationException("자체 미지원(하드게이트 태그드 발견) — Hiker");
	}

	@Override
	public HashtagPage fetchHashtagRecentPage(String tag, String pageId) {
		throw new UnsupportedOperationException("자체 미지원(하드게이트 해시태그 발견) — Hiker");
	}

	@Override
	public Map<String, ClipCounts> fetchClipCounts(String userId, int pages) {
		throw new UnsupportedOperationException("자체 미지원(clip 보강 폐지, embed 흡수) — Hiker");
	}

	@Override
	public MediaRef resolveMediaByUrl(String url) {
		throw new UnsupportedOperationException("자체 미지원(share 해소, 후속) — Hiker");
	}

	private <T> T run(String surface, Supplier<T> op) {
		guard(surface);
		try {
			T r = retry.call(surface, op);
			circuit.recordSuccess(surface);
			return r;
		} catch (SelfCrawlException e) {
			SelfCrawlException withSurface = e.withSurface(surface);
			recordIfBlock(surface, withSurface);
			throw withSurface;
		}
	}

	private void guard(String surface) {
		if (circuit.isOpen(surface)) {
			throw new SelfCrawlException(SelfErrorClass.OTHER, "서킷 열림: " + surface, null, surface);
		}
	}

	private void recordIfBlock(String surface, SelfCrawlException e) {
		switch (e.errorClass()) {
			case RECOVERABLE_401, RATE_LIMIT_429, TRANSPORT, LOGIN_WALL, FORBIDDEN_403 ->
					circuit.recordBlock(surface);
			default -> {
				// NOT_FOUND·STRUCTURAL_400·OTHER은 서킷 카운트 안 함
			}
		}
	}
}
