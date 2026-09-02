package com.celfit.instagram.source;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 콜 단위 토글 라우팅 — 이미 완성된(각자 폴백 정책을 갖춘) InstagramSource 빈 2개 사이를 순수하게
 * 위임한다. 새 폴백·재시도 로직을 만들지 않는다 — 실제 자체크롤·Hiker 우선순위 동작은 onSource·
 * offSource 각자가 이미 갖고 있다(예: {@link FailoverInstagramSource}·{@link HikerFirstInstagramSource}).
 *
 * <p>toggle은 매 콜 재평가한다(보통 TTL 캐시를 감싼 BooleanSupplier — IgSourceSettings 등) — 그래서
 * 전환이 재배포 없이 그 TTL만큼의 지연으로 반영된다. monitoring HikerConfig가 이 클래스로 "사용자
 * 트리거 비동기 흐름 전용" 라우팅 빈(userTriggeredInstagramSource)을 조립한다: toggle=false(시드)면
 * Hiker 1순위(syncInstagramSource와 동형)로, true면 자체 1순위(instagramSource와 동형)로 위임한다
 * (2026-09 사용자 트리거 도입 시점 토글 — 새벽 스케줄 트리거의 self 1순위 개통과 시점을 분리).
 */
public class ToggledInstagramSource implements InstagramSource {

	private final InstagramSource onSource;
	private final InstagramSource offSource;
	private final BooleanSupplier toggle;

	public ToggledInstagramSource(InstagramSource onSource, InstagramSource offSource, BooleanSupplier toggle) {
		this.onSource = onSource;
		this.offSource = offSource;
		this.toggle = toggle;
	}

	private InstagramSource delegate() {
		return toggle.getAsBoolean() ? onSource : offSource;
	}

	@Override
	public ProfileInfo fetchProfile(String username) {
		return delegate().fetchProfile(username);
	}

	@Override
	public AuthorInfo fetchAuthorProfile(String userId) {
		return delegate().fetchAuthorProfile(userId);
	}

	@Override
	public List<PostInfo> fetchRecentPosts(String username, String userId, int pages) {
		return delegate().fetchRecentPosts(username, userId, pages);
	}

	@Override
	public Map<String, ClipCounts> fetchClipCounts(String userId, int pages) {
		return delegate().fetchClipCounts(userId, pages);
	}

	@Override
	public TaggedPage fetchTaggedPage(String userId, String pageId) {
		return delegate().fetchTaggedPage(userId, pageId);
	}

	@Override
	public HashtagPage fetchHashtagRecentPage(String tag, String pageId) {
		return delegate().fetchHashtagRecentPage(tag, pageId);
	}

	@Override
	public PostInfo fetchPost(String shortCode) {
		return delegate().fetchPost(shortCode);
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages) {
		return delegate().fetchComments(shortCode, postUsername, pages);
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages,
			Set<String> knownCommentIds) {
		return delegate().fetchComments(shortCode, postUsername, pages, knownCommentIds);
	}

	@Override
	public MediaRef resolveMediaByUrl(String url) {
		return delegate().resolveMediaByUrl(url);
	}
}
