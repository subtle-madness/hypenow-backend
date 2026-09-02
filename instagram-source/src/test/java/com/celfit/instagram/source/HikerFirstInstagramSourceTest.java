package com.celfit.instagram.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 사용자 대면 동기 경로 정책 검증 — Hiker 1순위 + 장애 시에만 self로 구조(rescue). 자체 1순위 +
 * Hiker 폴백(async 기본 경로, {@link FailoverInstagramSource})의 정반대다.
 */
class HikerFirstInstagramSourceTest {

	private static PostInfo post(String shortCode) {
		return new PostInfo(shortCode, "acct", null, null, null, "REELS", null, null,
				null, null, null, null, null, null, null, null, null, null, null,
				false, false, false);
	}

	/** 모든 메서드가 호출되면 실패하는 베이스 — 각 테스트가 관찰할 메서드만 익명 클래스로 덮어쓴다. */
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

	private static final class RecordingMetrics implements InstagramSourceMetrics {

		final List<String> records = new ArrayList<>();

		@Override
		public void record(String path, String backend, String outcome) {
			records.add(path + "|" + backend + "|" + outcome);
		}
	}

	@Test
	void hiker가_정상이면_self는_한_번도_호출되지_않는다() {
		PostInfo hikerPost = post("HIKER");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return hikerPost;
			}
		};
		InstagramSource self = new ThrowingSource("self");   // 호출되면 AssertionError
		HikerFirstInstagramSource source = new HikerFirstInstagramSource(hiker, self, path -> true,
				InstagramSourceMetrics.NOOP);

		assertThat(source.fetchPost("ABC")).isSameAs(hikerPost);
	}

	@Test
	void self가_없어도_hiker_단독으로_정상_동작한다() {
		PostInfo hikerPost = post("HIKER");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return hikerPost;
			}
		};
		HikerFirstInstagramSource source = new HikerFirstInstagramSource(hiker);

		assertThat(source.fetchPost("ABC")).isSameAs(hikerPost);
	}

	@Test
	void hiker가_벤더_장애로_실패하면_self가_구조한다() {
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw new HikerFetchException("Hiker 500");
			}
		};
		PostInfo selfPost = post("SELF");
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return selfPost;
			}
		};
		HikerFirstInstagramSource source = new HikerFirstInstagramSource(hiker, self, path -> true,
				InstagramSourceMetrics.NOOP);

		assertThat(source.fetchPost("ABC")).isSameAs(selfPost);
	}

	@Test
	void 구조_성공은_self_rescue로_관측된다() {
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw new HikerFetchException("Hiker 500");
			}
		};
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return post("SELF");
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		HikerFirstInstagramSource source = new HikerFirstInstagramSource(hiker, self, path -> true, metrics);

		source.fetchPost("ABC");

		assertThat(metrics.records).containsExactly("fetchPost|self|rescue");
	}

	@Test
	void 정상_hiker_성공은_hiker_ok로_관측된다() {
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return post("HIKER");
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		HikerFirstInstagramSource source =
				new HikerFirstInstagramSource(hiker, new ThrowingSource("self"), path -> true, metrics);

		source.fetchPost("ABC");

		assertThat(metrics.records).containsExactly("fetchPost|hiker|ok");
	}

	@Test
	void hiker와_self가_둘다_실패하면_원래_hiker_예외가_전파된다() {
		HikerFetchException hikerFailure = new HikerFetchException("Hiker 500");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw hikerFailure;
			}
		};
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw new IllegalStateException("자체도 실패");
			}
		};
		HikerFirstInstagramSource source = new HikerFirstInstagramSource(hiker, self, path -> true,
				InstagramSourceMetrics.NOOP);

		assertThatThrownBy(() -> source.fetchPost("ABC")).isSameAs(hikerFailure);
	}

	@Test
	void 하드게이트_경로는_self_실패로_처리돼_원래_hiker_예외가_전파된다() {
		// fetchTaggedPage 등 self 미지원 표면 — self가 UnsupportedOperationException을 던져도
		// "구조 실패"로 동일하게 처리해 원래 Hiker 예외를 그대로 던진다(별도 하드게이트 분기 불필요).
		HikerFetchException hikerFailure = new HikerFetchException("Hiker 500");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public TaggedPage fetchTaggedPage(String userId, String pageId) {
				throw hikerFailure;
			}
		};
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public TaggedPage fetchTaggedPage(String userId, String pageId) {
				throw new UnsupportedOperationException("하드게이트 표면");
			}
		};
		HikerFirstInstagramSource source = new HikerFirstInstagramSource(hiker, self, path -> true,
				InstagramSourceMetrics.NOOP);

		assertThatThrownBy(() -> source.fetchTaggedPage("123", null)).isSameAs(hikerFailure);
	}

	@Test
	void 구조_실패는_hiker_rescue_failed로_관측된다() {
		HikerFetchException hikerFailure = new HikerFetchException("Hiker 500");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw hikerFailure;
			}
		};
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw new IllegalStateException("자체도 실패");
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		HikerFirstInstagramSource source = new HikerFirstInstagramSource(hiker, self, path -> true, metrics);

		assertThatThrownBy(() -> source.fetchPost("ABC"));

		assertThat(metrics.records).containsExactly("fetchPost|hiker|rescue-failed");
	}

	@Test
	void SubjectNotFoundException은_self_재시도_없이_그대로_전파된다() {
		SubjectNotFoundException notFound = new SubjectNotFoundException("게시물 없음");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw notFound;
			}
		};
		InstagramSource self = new ThrowingSource("self");   // 호출되면 AssertionError
		HikerFirstInstagramSource source = new HikerFirstInstagramSource(hiker, self, path -> true,
				InstagramSourceMetrics.NOOP);

		assertThatThrownBy(() -> source.fetchPost("ABC")).isSameAs(notFound);
	}

	@Test
	void PrivateAccountException도_self_재시도_없이_그대로_전파된다() {
		PrivateAccountException privateAccount = new PrivateAccountException("비공개 계정");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public ProfileInfo fetchProfile(String username) {
				throw privateAccount;
			}
		};
		InstagramSource self = new ThrowingSource("self");   // 호출되면 AssertionError
		HikerFirstInstagramSource source = new HikerFirstInstagramSource(hiker, self, path -> true,
				InstagramSourceMetrics.NOOP);

		assertThatThrownBy(() -> source.fetchProfile("acct")).isSameAs(privateAccount);
	}

	@Test
	void 토글_off면_hiker가_실패해도_self로_구조하지_않는다() {
		HikerFetchException hikerFailure = new HikerFetchException("Hiker 500");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw hikerFailure;
			}
		};
		InstagramSource self = new ThrowingSource("self");   // 호출되면 AssertionError
		HikerFirstInstagramSource source = new HikerFirstInstagramSource(hiker, self, path -> false,
				InstagramSourceMetrics.NOOP);

		assertThatThrownBy(() -> source.fetchPost("ABC")).isSameAs(hikerFailure);
	}

	@Test
	void path_predicate에서_빠진_경로만_구조를_시도하지_않는다() {
		HikerFetchException hikerFailure = new HikerFetchException("Hiker 500");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw hikerFailure;
			}

			@Override
			public ProfileInfo fetchProfile(String username) {
				throw new HikerFetchException("Hiker 500 profile");
			}
		};
		ProfileInfo selfProfile = new ProfileInfo("acct", "1", 10L, 5L, 2L, null, null, null, false, null);
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw new AssertionError("self.fetchPost 호출되면 안 됨 — predicate에서 제외됨");
			}

			@Override
			public ProfileInfo fetchProfile(String username) {
				return selfProfile;
			}
		};
		// fetchPost만 제외 — fetchProfile은 여전히 구조 대상.
		HikerFirstInstagramSource source =
				new HikerFirstInstagramSource(hiker, self, path -> !"fetchPost".equals(path), InstagramSourceMetrics.NOOP);

		assertThatThrownBy(() -> source.fetchPost("ABC")).isSameAs(hikerFailure);
		assertThat(source.fetchProfile("acct")).isSameAs(selfProfile);
	}
}
