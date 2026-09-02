package com.celfit.instagram.source;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 인스타그램 수집의 안정된 계약 하나 — 자체크롤·Hiker 백엔드를 이 뒤에 두고 폴백을 모듈 안에 가둔다
 * (스펙 2026-08-31-instagram-hiker-selfcrawl-hybrid-design.md §4). 소비자는 어느 백엔드가
 * 응답했는지 몰라도 되고, 자기 스토어에만 저장한다(경계 규칙 위반 없음).
 *
 * <p>마일스톤 A는 Hiker 단독 구현({@link FailoverInstagramSource} → {@link HikerBackend})이라
 * 메서드 이름·시그니처를 monitoring 기존 HikerClient와 동일하게 유지한다(행동 변화 0). 하드게이트
 * 3종(fetchTaggedPage·fetchAuthorProfile·fetchHashtagRecentPage)은 자체 백엔드가 없어 마일스톤 B
 * 이후에도 Hiker 단독으로 남는다.
 */
public interface InstagramSource {

	ProfileInfo fetchProfile(String username);

	AuthorInfo fetchAuthorProfile(String userId);

	List<PostInfo> fetchRecentPosts(String username, String userId, int pages);

	Map<String, ClipCounts> fetchClipCounts(String userId, int pages);

	TaggedPage fetchTaggedPage(String userId, String pageId);

	HashtagPage fetchHashtagRecentPage(String tag, String pageId);

	PostInfo fetchPost(String shortCode);

	CommentsFetch fetchComments(String shortCode, String postUsername, int pages);

	CommentsFetch fetchComments(String shortCode, String postUsername, int pages, Set<String> knownCommentIds);

	MediaRef resolveMediaByUrl(String url);
}
