package com.celfit.instagram.source;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 수집 정책 계층 — 자체크롤 1순위 + Hiker 폴백 + 서킷을 이 뒤에 가둔다(스펙 §4). 소비자는 항상 이
 * 타입을 주입받아, 백엔드/표면 선택을 모른다.
 *
 * <p>마일스톤 A: 자체크롤 백엔드가 아직 없어 모든 경로를 {@code hiker}로 위임한다(행동 변화 0).
 * 마일스톤 B가 SelfCrawlBackend·표면 사다리·서킷·에러 taxonomy를 여기에 채운다 — 그때도 소비자
 * 주입 지점은 이 타입 그대로라 소비자 코드는 다시 바뀌지 않는다.
 */
public class FailoverInstagramSource implements InstagramSource {

	private final InstagramSource hiker;

	public FailoverInstagramSource(InstagramSource hiker) {
		this.hiker = hiker;
	}

	@Override
	public ProfileInfo fetchProfile(String username) {
		return hiker.fetchProfile(username);
	}

	@Override
	public AuthorInfo fetchAuthorProfile(String userId) {
		return hiker.fetchAuthorProfile(userId);
	}

	@Override
	public List<PostInfo> fetchRecentPosts(String username, String userId, int pages) {
		return hiker.fetchRecentPosts(username, userId, pages);
	}

	@Override
	public Map<String, ClipCounts> fetchClipCounts(String userId, int pages) {
		return hiker.fetchClipCounts(userId, pages);
	}

	@Override
	public TaggedPage fetchTaggedPage(String userId, String pageId) {
		return hiker.fetchTaggedPage(userId, pageId);
	}

	@Override
	public HashtagPage fetchHashtagRecentPage(String tag, String pageId) {
		return hiker.fetchHashtagRecentPage(tag, pageId);
	}

	@Override
	public PostInfo fetchPost(String shortCode) {
		return hiker.fetchPost(shortCode);
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages) {
		return hiker.fetchComments(shortCode, postUsername, pages);
	}

	@Override
	public CommentsFetch fetchComments(String shortCode, String postUsername, int pages,
			Set<String> knownCommentIds) {
		return hiker.fetchComments(shortCode, postUsername, pages, knownCommentIds);
	}

	@Override
	public MediaRef resolveMediaByUrl(String url) {
		return hiker.resolveMediaByUrl(url);
	}
}
