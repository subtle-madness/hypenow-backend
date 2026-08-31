package com.celfit.instagram.source;

import com.celfit.instagram.source.self.SelfCrawlException;
import com.celfit.instagram.source.self.SelfErrorClass;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 수집 정책 계층 — 자체크롤 1순위 + Hiker 폴백 + 에러 taxonomy 라우팅(스펙 §4·§8). selfEnabled는
 * BooleanSupplier라 매 콜 재확인한다(app_setting 런타임 토글·킬스위치가 재시작 없이 반영). 결과는
 * InstagramSourceMetrics로 관측한다. selfEnabled가 false를 주면 전량 Hiker(행동 변화 0).
 */
public class FailoverInstagramSource implements InstagramSource {

	private final InstagramSource self;
	private final InstagramSource hiker;
	private final BooleanSupplier selfEnabled;
	private final InstagramSourceMetrics metrics;

	/** 마일스톤 A 호환 — 자체 없이 Hiker 단독. */
	public FailoverInstagramSource(InstagramSource hiker) {
		this(null, hiker, () -> false, InstagramSourceMetrics.NOOP);
	}

	public FailoverInstagramSource(InstagramSource self, InstagramSource hiker, BooleanSupplier selfEnabled) {
		this(self, hiker, selfEnabled, InstagramSourceMetrics.NOOP);
	}

	public FailoverInstagramSource(InstagramSource self, InstagramSource hiker, BooleanSupplier selfEnabled,
			InstagramSourceMetrics metrics) {
		this.self = self;
		this.hiker = hiker;
		this.selfEnabled = selfEnabled;
		this.metrics = metrics;
	}

	@Override
	public ProfileInfo fetchProfile(String username) {
		return route("fetchProfile", () -> self.fetchProfile(username), () -> hiker.fetchProfile(username));
	}

	@Override
	public AuthorInfo fetchAuthorProfile(String userId) {
		return route("fetchAuthorProfile", () -> self.fetchAuthorProfile(userId),
				() -> hiker.fetchAuthorProfile(userId));
	}

	@Override
	public List<PostInfo> fetchRecentPosts(String username, String userId, int pages) {
		return route("fetchRecentPosts", () -> self.fetchRecentPosts(username, userId, pages),
				() -> hiker.fetchRecentPosts(username, userId, pages));
	}

	@Override
	public Map<String, ClipCounts> fetchClipCounts(String userId, int pages) {
		return route("fetchClipCounts", () -> self.fetchClipCounts(userId, pages),
				() -> hiker.fetchClipCounts(userId, pages));
	}

	@Override
	public TaggedPage fetchTaggedPage(String userId, String pageId) {
		return route("fetchTaggedPage", () -> self.fetchTaggedPage(userId, pageId),
				() -> hiker.fetchTaggedPage(userId, pageId));
	}

	@Override
	public HashtagPage fetchHashtagRecentPage(String tag, String pageId) {
		return route("fetchHashtagRecentPage", () -> self.fetchHashtagRecentPage(tag, pageId),
				() -> hiker.fetchHashtagRecentPage(tag, pageId));
	}

	@Override
	public PostInfo fetchPost(String shortCode) {
		return route("fetchPost", () -> self.fetchPost(shortCode), () -> hiker.fetchPost(shortCode));
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages) {
		return route("fetchComments", () -> self.fetchComments(shortCode, postUsername, pages),
				() -> hiker.fetchComments(shortCode, postUsername, pages));
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages,
			Set<String> knownCommentIds) {
		return route("fetchComments", () -> self.fetchComments(shortCode, postUsername, pages, knownCommentIds),
				() -> hiker.fetchComments(shortCode, postUsername, pages, knownCommentIds));
	}

	@Override
	public MediaRef resolveMediaByUrl(String url) {
		return route("resolveMediaByUrl", () -> self.resolveMediaByUrl(url), () -> hiker.resolveMediaByUrl(url));
	}

	/**
	 * 자체 1순위 → 실패 시 Hiker(매 콜 selfEnabled 재확인). NOT_FOUND는 부재로 종료(폴백 안 함,
	 * SubjectNotFoundException 변환), 그 외 자체 실패·미지원은 Hiker 폴백. 성공/폴백 결과를 관측한다
	 * (Hiker 자체 예외는 그대로 전파, 미기록).
	 */
	private <T> T route(String path, Supplier<T> selfCall, Supplier<T> hikerCall) {
		if (self == null || !selfEnabled.getAsBoolean()) {
			T r = hikerCall.get();
			metrics.record(path, "hiker", "ok");
			return r;
		}
		try {
			T r = selfCall.get();
			metrics.record(path, "self", "ok");
			return r;
		} catch (UnsupportedOperationException e) {
			T r = hikerCall.get();
			metrics.record(path, "hiker", "hardgate");
			return r;
		} catch (SelfCrawlException e) {
			if (e.errorClass() == SelfErrorClass.NOT_FOUND) {
				metrics.record(path, "self", "notfound");
				throw new SubjectNotFoundException(e.getMessage());
			}
			T r = hikerCall.get();
			metrics.record(path, "hiker", "fallback:" + e.errorClass());
			return r;
		}
	}
}
