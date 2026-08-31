package com.celfit.instagram.source;

import com.celfit.instagram.source.self.SelfCrawlException;
import com.celfit.instagram.source.self.SelfErrorClass;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 수집 정책 계층 — 자체크롤 1순위 + Hiker 폴백 + 에러 taxonomy 라우팅(스펙 §4·§8). 소비자는 항상
 * 이 타입을 주입받아 백엔드/표면 선택을 모른다. selfEnabled=false(기본)면 전량 Hiker(행동 변화 0).
 * self가 UnsupportedOperationException(하드게이트·미구현) 또는 폴백류 SelfCrawlException을 던지면
 * Hiker로, NOT_FOUND면 부재로 종료(폴백 안 함 — SubjectNotFoundException으로 변환해 소비자 계약 유지).
 * 마일스톤 B는 selfEnabled=false로 배선 → 런타임 동작 불변. 개통은 마일스톤 C.
 */
public class FailoverInstagramSource implements InstagramSource {

	private final InstagramSource self;
	private final InstagramSource hiker;
	private final boolean selfEnabled;

	/** 마일스톤 A 호환 — 자체 없이 Hiker 단독. */
	public FailoverInstagramSource(InstagramSource hiker) {
		this(null, hiker, false);
	}

	public FailoverInstagramSource(InstagramSource self, InstagramSource hiker, boolean selfEnabled) {
		this.self = self;
		this.hiker = hiker;
		this.selfEnabled = selfEnabled;
	}

	@Override
	public ProfileInfo fetchProfile(String username) {
		return route(() -> self.fetchProfile(username), () -> hiker.fetchProfile(username));
	}

	@Override
	public AuthorInfo fetchAuthorProfile(String userId) {
		return route(() -> self.fetchAuthorProfile(userId), () -> hiker.fetchAuthorProfile(userId));
	}

	@Override
	public List<PostInfo> fetchRecentPosts(String username, String userId, int pages) {
		return route(() -> self.fetchRecentPosts(username, userId, pages),
				() -> hiker.fetchRecentPosts(username, userId, pages));
	}

	@Override
	public Map<String, ClipCounts> fetchClipCounts(String userId, int pages) {
		return route(() -> self.fetchClipCounts(userId, pages), () -> hiker.fetchClipCounts(userId, pages));
	}

	@Override
	public TaggedPage fetchTaggedPage(String userId, String pageId) {
		return route(() -> self.fetchTaggedPage(userId, pageId), () -> hiker.fetchTaggedPage(userId, pageId));
	}

	@Override
	public HashtagPage fetchHashtagRecentPage(String tag, String pageId) {
		return route(() -> self.fetchHashtagRecentPage(tag, pageId),
				() -> hiker.fetchHashtagRecentPage(tag, pageId));
	}

	@Override
	public PostInfo fetchPost(String shortCode) {
		return route(() -> self.fetchPost(shortCode), () -> hiker.fetchPost(shortCode));
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages) {
		return route(() -> self.fetchComments(shortCode, postUsername, pages),
				() -> hiker.fetchComments(shortCode, postUsername, pages));
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages,
			Set<String> knownCommentIds) {
		return route(() -> self.fetchComments(shortCode, postUsername, pages, knownCommentIds),
				() -> hiker.fetchComments(shortCode, postUsername, pages, knownCommentIds));
	}

	@Override
	public MediaRef resolveMediaByUrl(String url) {
		return route(() -> self.resolveMediaByUrl(url), () -> hiker.resolveMediaByUrl(url));
	}

	/**
	 * 자체 1순위 → 실패 시 Hiker. 자체 비활성/부재면 곧장 Hiker. 자체 NOT_FOUND는 부재로
	 * 종료(폴백 안 함, SubjectNotFoundException 변환), 그 외 자체 실패·미지원은 Hiker 폴백.
	 */
	private <T> T route(Supplier<T> selfCall, Supplier<T> hikerCall) {
		if (!selfEnabled || self == null) {
			return hikerCall.get();
		}
		try {
			return selfCall.get();
		} catch (UnsupportedOperationException e) {
			return hikerCall.get();
		} catch (SelfCrawlException e) {
			if (e.errorClass() == SelfErrorClass.NOT_FOUND) {
				throw new SubjectNotFoundException(e.getMessage());
			}
			return hikerCall.get();
		}
	}
}
