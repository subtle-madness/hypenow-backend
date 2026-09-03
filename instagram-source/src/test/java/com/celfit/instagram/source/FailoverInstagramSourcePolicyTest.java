package com.celfit.instagram.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.self.SelfCrawlException;
import com.celfit.instagram.source.self.SelfErrorClass;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, () -> false);
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
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, () -> true);
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
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, () -> true);
		assertThat(source.fetchPost("ABC")).isSameAs(hikerPost);
	}

	@Test
	void self가_예상외_RuntimeException을_던지면_hiker로_폴백한다() {
		// SelfCrawlException·UnsupportedOperationException 외의 RuntimeException(예: 프록시 URL 파싱
		// 실패가 어딘가에서 안 잡히고 샌 경우)도 폴백망에 태워야 한다 — F2 결함(캐치올 부재)의 회귀 방지.
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw new IllegalStateException("예상 못한 런타임 오류");
			}
		};
		PostInfo hikerPost = post("HIKER");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return hikerPost;
			}
		};
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, () -> true);
		assertThat(source.fetchPost("ABC")).isSameAs(hikerPost);
	}

	@Test
	void 예상외_RuntimeException_폴백은_fallback_UNEXPECTED로_관측된다() {
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw new IllegalStateException("예상 못한 런타임 오류");
			}
		};
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return post("HIKER");
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, () -> true, metrics);

		source.fetchPost("ABC");

		assertThat(metrics.records).containsExactly("fetchPost|hiker|fallback:UNEXPECTED");
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
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, () -> true);
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
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, () -> true);
		assertThat(source.fetchTaggedPage("123", null)).isSameAs(hikerPage);
	}

	@Test
	void selfEnabled는_매_콜마다_재확인된다() {
		PostInfo selfPost = post("SELF");
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return selfPost;
			}
		};
		PostInfo hikerPost = post("HIKER");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return hikerPost;
			}
		};
		AtomicBoolean enabled = new AtomicBoolean(false);
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, enabled::get);

		// 첫 콜: off → hiker
		assertThat(source.fetchPost("ABC")).isSameAs(hikerPost);
		// 런타임 토글 on → 재시작 없이 다음 콜부터 self
		enabled.set(true);
		assertThat(source.fetchPost("ABC")).isSameAs(selfPost);
	}

	/**
	 * 관측 훅 계약 — path·backend·outcome 3튜플을 그대로 기록한다. durationRecords는 recordDuration이
	 * record와 같은 (path, backend, outcome) 태그로 짝을 이뤄 불리는지 검증하는 용도(elapsedNanos는
	 * 값 자체를 검증하지 않고 "호출됐다"만 태그 문자열로 확인한다).
	 */
	private static final class RecordingMetrics implements InstagramSourceMetrics {

		final List<String> records = new ArrayList<>();
		final List<String> durationRecords = new ArrayList<>();

		@Override
		public void record(String path, String backend, String outcome) {
			records.add(path + "|" + backend + "|" + outcome);
		}

		@Override
		public void recordDuration(String path, String backend, String outcome, long elapsedNanos) {
			durationRecords.add(path + "|" + backend + "|" + outcome);
		}
	}

	@Test
	void self_성공은_self_ok로_관측된다() {
		PostInfo selfPost = post("SELF");
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return selfPost;
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		FailoverInstagramSource source =
				new FailoverInstagramSource(self, new ThrowingSource("hiker"), () -> true, metrics);

		source.fetchPost("ABC");

		assertThat(metrics.records).containsExactly("fetchPost|self|ok");
		assertThat(metrics.durationRecords).containsExactly("fetchPost|self|ok");
	}

	@Test
	void 폴백은_hiker_fallback_에러클래스로_관측된다() {
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw new SelfCrawlException(SelfErrorClass.STRUCTURAL_400, "계정 버그 400");
			}
		};
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return post("HIKER");
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, () -> true, metrics);

		source.fetchPost("ABC");

		assertThat(metrics.records).containsExactly("fetchPost|hiker|fallback:STRUCTURAL_400");
		assertThat(metrics.durationRecords).containsExactly("fetchPost|hiker|fallback:STRUCTURAL_400");
	}

	/** F9 — 댓글 미완주(complete=false)는 self 호출 자체는 성공이라 폴백 안 하지만 "partial"로 구분 관측된다. */
	@Test
	void self_댓글_미완주는_self_partial로_관측된다() {
		CommentInfo comment = new CommentInfo("1", "u", "text", null, Instant.now(), null);
		CommentsFetch partial = new CommentsFetch(List.of(comment), false);
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public CommentsFetch fetchComments(String shortCode, String postUsername, int pages) {
				return partial;
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		FailoverInstagramSource source =
				new FailoverInstagramSource(self, new ThrowingSource("hiker"), () -> true, metrics);

		CommentsFetch result = source.fetchComments("ABC", "acct", 3);

		assertThat(result).isSameAs(partial);
		assertThat(metrics.records).containsExactly("fetchComments|self|partial");
	}

	/** 완주(complete=true)는 그대로 "ok" — partial 구분이 정상 케이스를 오염시키지 않는다. */
	@Test
	void self_댓글_완주는_기존대로_self_ok로_관측된다() {
		CommentInfo comment = new CommentInfo("1", "u", "text", null, Instant.now(), null);
		CommentsFetch complete = new CommentsFetch(List.of(comment), true);
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public CommentsFetch fetchComments(String shortCode, String postUsername, int pages) {
				return complete;
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		FailoverInstagramSource source =
				new FailoverInstagramSource(self, new ThrowingSource("hiker"), () -> true, metrics);

		source.fetchComments("ABC", "acct", 3);

		assertThat(metrics.records).containsExactly("fetchComments|self|ok");
	}

	@Test
	void NOT_FOUND는_self_notfound로_관측된다() {
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw new SelfCrawlException(SelfErrorClass.NOT_FOUND, "게시물 부재 404");
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		FailoverInstagramSource source =
				new FailoverInstagramSource(self, new ThrowingSource("hiker"), () -> true, metrics);

		assertThatThrownBy(() -> source.fetchPost("ABC")).isInstanceOf(SubjectNotFoundException.class);

		assertThat(metrics.records).containsExactly("fetchPost|self|notfound");
		// NOT_FOUND는 예외를 던지기 직전에 기록된다 — duration도 예외 전파와 무관하게 함께 남아야 한다.
		assertThat(metrics.durationRecords).containsExactly("fetchPost|self|notfound");
	}

	// ── 경로별(표면별) 자체크롤 토글 — Predicate<String> 생성자(부분 개통 지원) ──────

	@Test
	void path_predicate에서_빠진_경로만_hiker로_가고_나머지는_self를_그대로_쓴다() {
		PostInfo selfPost = post("SELF");
		CommentInfo comment = new CommentInfo("1", "u", "text", null, Instant.now(), null);
		CommentsFetch selfComments = new CommentsFetch(List.of(comment), true);
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				throw new AssertionError("self.fetchPost 호출되면 안 됨 — path_predicate에서 제외됨");
			}

			@Override
			public CommentsFetch fetchComments(String shortCode, String postUsername, int pages) {
				return selfComments;
			}
		};
		PostInfo hikerPost = post("HIKER");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return hikerPost;
			}
		};
		// fetchPost만 제외 — 나머지 경로(fetchComments 등)는 여전히 self.
		FailoverInstagramSource source =
				new FailoverInstagramSource(self, hiker, (String path) -> !"fetchPost".equals(path));

		assertThat(source.fetchPost("ABC")).isSameAs(hikerPost);
		assertThat(source.fetchComments("ABC", "acct", 1)).isSameAs(selfComments);
	}

	@Test
	void path_predicate가_전_경로_false면_경로_무관하게_전부_hiker다() {
		InstagramSource self = new ThrowingSource("self");
		PostInfo hikerPost = post("HIKER");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return hikerPost;
			}
		};
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, (String path) -> false);

		assertThat(source.fetchPost("ABC")).isSameAs(hikerPost);
	}

	@Test
	void path_predicate는_매_콜마다_재확인된다() {
		PostInfo selfPost = post("SELF");
		InstagramSource self = new ThrowingSource("self") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return selfPost;
			}
		};
		PostInfo hikerPost = post("HIKER");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return hikerPost;
			}
		};
		AtomicBoolean pathEnabled = new AtomicBoolean(false);
		FailoverInstagramSource source = new FailoverInstagramSource(self, hiker, path -> pathEnabled.get());

		assertThat(source.fetchPost("ABC")).isSameAs(hikerPost);
		pathEnabled.set(true);
		assertThat(source.fetchPost("ABC")).isSameAs(selfPost);
	}

	@Test
	void path_predicate로_제외된_경로는_metrics에_기존_전역_off와_동일하게_관측된다() {
		InstagramSource self = new ThrowingSource("self");
		InstagramSource hiker = new ThrowingSource("hiker") {
			@Override
			public PostInfo fetchPost(String shortCode) {
				return post("HIKER");
			}
		};
		RecordingMetrics metrics = new RecordingMetrics();
		FailoverInstagramSource source =
				new FailoverInstagramSource(self, hiker, (String path) -> false, metrics);

		source.fetchPost("ABC");

		assertThat(metrics.records).containsExactly("fetchPost|hiker|ok");
	}
}
