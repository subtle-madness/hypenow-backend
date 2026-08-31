package com.celfit.instagram.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.self.SelfCrawlException;
import com.celfit.instagram.source.self.SelfErrorClass;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 자체 1순위 + Hiker 폴백 + 에러 taxonomy 라우팅 정책 검증(스펙 §8-1). */
class FailoverInstagramSourcePolicyTest {

	private static PostInfo post(String shortCode) {
		return new PostInfo(shortCode, "acct", null, null, null, "REELS", null, null,
				null, null, null, null, null, null, null, null, null, null, null,
				false, false, false);
	}

	/** 모든 메서드가 실패하는 베이스 — 각 테스트가 관찰할 메서드만 익명 클래스로 덮어쓴다. */
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
	void 토글_off면_self를_건드리지_않고_전량_hiker로_간다() {
		InstagramSource self = new ThrowingSource("self");
		PostInfo hikerPost = post("HIKER");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return hikerPost;
			}
		};
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, false);
		assertThat(source.fetchPost("ABC")).isSameAs(hikerPost);
	}

	@Test
	void 토글_on이면_self_성공_결과를_그대로_반환하고_hiker는_호출하지_않는다() {
		PostInfo selfPost = post("SELF");
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return selfPost;
			}
		};
		InstagramSource hiker = new ThrowingSource("hiker");
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, true);
		assertThat(source.fetchPost("ABC")).isSameAs(selfPost);
	}

	@Test
	void self가_폴백류_에러를_던지면_hiker_결과로_폴백한다() {
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw new SelfCrawlException(SelfErrorClass.STRUCTURAL_400, "계정 버그 400");
			}
		};
		PostInfo hikerPost = post("HIKER");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return hikerPost;
			}
		};
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, true);
		assertThat(source.fetchPost("ABC")).isSameAs(hikerPost);
	}

	@Test
	void self가_NOT_FOUND면_폴백하지_않고_SubjectNotFoundException으로_종료한다() {
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw new SelfCrawlException(SelfErrorClass.NOT_FOUND, "게시물 부재 404");
			}
		};
		InstagramSource hiker = new ThrowingSource("hiker");
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, true);
		assertThatThrownBy(() -> source.fetchPost("ABC"))
				.isInstanceOf(SubjectNotFoundException.class)
				.hasMessage("게시물 부재 404");
	}

	@Test
	void self가_미지원_UnsupportedOperationException이면_hiker로_라우팅한다() {
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public TaggedPage fetchTaggedPage(String userId, String pageId) {
				throw new UnsupportedOperationException("하드게이트 표면");
			}
		};
		TaggedPage hikerPage = new TaggedPage(List.of(), null);
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public TaggedPage fetchTaggedPage(String userId, String pageId) {
				return hikerPage;
			}
		};
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, true);
		assertThat(source.fetchTaggedPage("123", null)).isSameAs(hikerPage);
	}
}
