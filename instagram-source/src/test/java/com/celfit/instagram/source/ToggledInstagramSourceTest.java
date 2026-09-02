package com.celfit.instagram.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 콜 단위 토글 라우팅 검증 — 순수 위임(새 폴백 로직 없음)이라 각 콜마다 toggle을 재평가해
 * onSource·offSource 중 하나로만 간다는 것만 확인하면 충분하다(각 위임 대상의 내부 폴백 동작은
 * FailoverInstagramSourceTest·HikerFirstInstagramSourceTest가 이미 검증한다).
 */
class ToggledInstagramSourceTest {

	private static PostInfo post(String shortCode) {
		return new PostInfo(shortCode, "acct", null, null, null, "REELS", null, null,
				null, null, null, null, null, null, null, null, null, null, null,
				false, false, false);
	}

	/** 호출되면 실패하는 베이스 — 각 테스트가 관찰할 메서드만 익명 클래스로 덮어쓴다. */
	private static class ThrowingSource implements InstagramSource {

		private final String name;

		ThrowingSource(String name) {
			this.name = name;
		}

		@Override
		public ProfileInfo fetchProfile(String username) {
			throw new AssertionError(name + ".fetchProfile 호출되면 안 됨");
		}

		@Override
		public AuthorInfo fetchAuthorProfile(String userId) {
			throw new AssertionError(name + ".fetchAuthorProfile 호출되면 안 됨");
		}

		@Override
		public List<PostInfo> fetchRecentPosts(String username, String userId, int pages) {
			throw new AssertionError(name + ".fetchRecentPosts 호출되면 안 됨");
		}

		@Override
		public Map<String, ClipCounts> fetchClipCounts(String userId, int pages) {
			throw new AssertionError(name + ".fetchClipCounts 호출되면 안 됨");
		}

		@Override
		public TaggedPage fetchTaggedPage(String userId, String pageId) {
			throw new AssertionError(name + ".fetchTaggedPage 호출되면 안 됨");
		}

		@Override
		public HashtagPage fetchHashtagRecentPage(String tag, String pageId) {
			throw new AssertionError(name + ".fetchHashtagRecentPage 호출되면 안 됨");
		}

		@Override
		public PostInfo fetchPost(String shortCode) {
			throw new AssertionError(name + ".fetchPost 호출되면 안 됨");
		}

		@Override
		public CommentsFetch fetchComments(String shortCode, String postUsername, int pages) {
			throw new AssertionError(name + ".fetchComments 호출되면 안 됨");
		}

		@Override
		public CommentsFetch fetchComments(String shortCode, String postUsername, int pages,
				Set<String> knownCommentIds) {
			throw new AssertionError(name + ".fetchComments(knownIds) 호출되면 안 됨");
		}

		@Override
		public MediaRef resolveMediaByUrl(String url) {
			throw new AssertionError(name + ".resolveMediaByUrl 호출되면 안 됨");
		}
	}

	@Test
	void toggle이_false면_offSource로만_위임된다() {
		PostInfo offPost = post("OFF");
		InstagramSource onSource = new ThrowingSource("on");   // 호출되면 AssertionError
		InstagramSource offSource = new ThrowingSource("off") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return offPost;
			}
		};
		ToggledInstagramSource routed = new ToggledInstagramSource(onSource, offSource, () -> false);

		assertThat(routed.fetchPost("ABC")).isSameAs(offPost);
	}

	@Test
	void toggle이_true면_onSource로만_위임된다() {
		PostInfo onPost = post("ON");
		InstagramSource onSource = new ThrowingSource("on") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return onPost;
			}
		};
		InstagramSource offSource = new ThrowingSource("off");   // 호출되면 AssertionError
		ToggledInstagramSource routed = new ToggledInstagramSource(onSource, offSource, () -> true);

		assertThat(routed.fetchPost("ABC")).isSameAs(onPost);
	}

	@Test
	void toggle은_콜마다_재평가된다() {
		PostInfo onPost = post("ON");
		PostInfo offPost = post("OFF");
		InstagramSource onSource = new ThrowingSource("on") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return onPost;
			}
		};
		InstagramSource offSource = new ThrowingSource("off") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return offPost;
			}
		};
		boolean[] state = {false};
		ToggledInstagramSource routed = new ToggledInstagramSource(onSource, offSource, () -> state[0]);

		assertThat(routed.fetchPost("ABC")).isSameAs(offPost);

		state[0] = true;
		assertThat(routed.fetchPost("ABC")).isSameAs(onPost);

		state[0] = false;
		assertThat(routed.fetchPost("ABC")).isSameAs(offPost);
	}

	@Test
	void 모든_메서드가_같은_delegate로_위임된다() {
		InstagramSource onSource = new ThrowingSource("on") {
			@Override
			public ProfileInfo fetchProfile(String username) {
				return new ProfileInfo(username, "1", 1L, 1L, 1L, null, null, null, false, null);
			}

			@Override
			public AuthorInfo fetchAuthorProfile(String userId) {
				return new AuthorInfo(userId, "author", null, 1L, null, null, null, null, false, null);
			}
		};
		InstagramSource offSource = new ThrowingSource("off");
		ToggledInstagramSource routed = new ToggledInstagramSource(onSource, offSource, () -> true);

		assertThat(routed.fetchProfile("acct").username()).isEqualTo("acct");
		assertThat(routed.fetchAuthorProfile("1").igUserId()).isEqualTo("1");
	}
}
