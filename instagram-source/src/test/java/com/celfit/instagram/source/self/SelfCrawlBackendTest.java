package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.CommentsFetch;
import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.ProfileInfo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * SelfCrawlBackend — 표면 fetcher 라우팅·미지원 경로·재시도/서킷 배선 검증. fetcher들이 구체
 * 클래스라 fake는 그들의 함수형 심(SelfFetch·SelfTransport)에 꽂는다 — 파싱 재검증이 아니라
 * 백엔드의 배선만 본다.
 */
class SelfCrawlBackendTest {

	/** 호출마다 큐에서 하나씩 꺼내 실행하는 fake 심 — 예외 던지기·정상 응답을 시나리오로 짠다. */
	private static final class ScriptedFetch implements EmbedPostFetcher.SelfFetch {
		final List<Supplier<SelfResponse>> script;
		int calls;

		ScriptedFetch(List<Supplier<SelfResponse>> script) {
			this.script = script;
		}

		@Override
		public SelfResponse fetch(String url, ProxyTier tier, Map<String, String> headers) {
			return script.get(Math.min(calls++, script.size() - 1)).get();
		}
	}

	private static Supplier<SelfResponse> throwing(SelfErrorClass errorClass) {
		return () -> {
			throw new SelfCrawlException(errorClass, "fake 실패: " + errorClass);
		};
	}

	private static Supplier<SelfResponse> ok(String body) {
		return () -> new SelfResponse(200, body);
	}

	// 소유자 span만 있으면 embed 파싱이 빈 셸 판정을 피한다 — 파싱 자체는 EmbedPostFetcherTest 몫.
	private static final String EMBED_OK = "<span class=\"UsernameText\">nasa</span>";
	private static final String WPI_OK = """
			{"data":{"user":{"username":"nasa","id":"42","is_private":false,
			"edge_followed_by":{"count":100},
			"edge_owner_to_timeline_media":{"count":1,"edges":[
			{"node":{"shortcode":"ABC","is_video":false,"taken_at_timestamp":1700000000}}]}}}}
			""";
	private static final String COMMENT_PAGE = "\"LSD\",[],{\"token\":\"TKN\"}";
	private static final String COMMENT_JSON = """
			{"data":{"xig_polaris_media":{"comments_connection":{
			"edges":[{"node":{"id":"c1","text":"hi","user":{"username":"u"},"created_at":100}}],
			"page_info":{"has_next_page":false}}}}}
			""";

	/** get은 게시물 페이지(LSD), post는 GraphQL 댓글 응답을 돌려주는 fake 전송. */
	private static final class FakeTransport implements SelfTransport {
		@Override
		public SelfResponse get(String url, ProxyTier tier, Map<String, String> headers) {
			return new SelfResponse(200, COMMENT_PAGE);
		}

		@Override
		public SelfResponse post(String url, String formBody, ProxyTier tier,
				Map<String, String> headers) {
			return new SelfResponse(200, COMMENT_JSON);
		}
	}

	private static SelfCrawlBackend backend(EmbedPostFetcher.SelfFetch embedFetch) {
		return backend(embedFetch, new ScriptedFetch(List.of(ok(WPI_OK))));
	}

	private static SelfCrawlBackend backend(EmbedPostFetcher.SelfFetch embedFetch,
			EmbedPostFetcher.SelfFetch wpiFetch) {
		return new SelfCrawlBackend(new EmbedPostFetcher(embedFetch),
				new WpiProfileFetcher(wpiFetch),
				new DirectCommentFetcher(new FakeTransport(), "DOC", "FRIENDLY"),
				new SurfaceCircuitBreaker(5), new SelfRetry(3));
	}

	@Test
	void fetchPost는_embed_fetcher로_위임한다() {
		PostInfo post = backend(new ScriptedFetch(List.of(ok(EMBED_OK)))).fetchPost("SHORT");

		assertThat(post.shortCode()).isEqualTo("SHORT");
		assertThat(post.username()).isEqualTo("nasa");
	}

	@Test
	void fetchProfile은_wpi_fetcher로_위임한다() {
		ProfileInfo profile = backend(new ScriptedFetch(List.of(ok(EMBED_OK))))
				.fetchProfile("nasa");

		assertThat(profile.username()).isEqualTo("nasa");
		assertThat(profile.userId()).isEqualTo("42");
		assertThat(profile.followers()).isEqualTo(100L);
	}

	@Test
	void fetchRecentPosts는_wpi_fetcher로_위임한다() {
		List<PostInfo> posts = backend(new ScriptedFetch(List.of(ok(EMBED_OK))))
				.fetchRecentPosts("nasa", "42", 3);

		assertThat(posts).hasSize(1);
		assertThat(posts.get(0).shortCode()).isEqualTo("ABC");
	}

	@Test
	void fetchComments_3인자는_comment_fetcher로_위임한다() {
		CommentsFetch result = backend(new ScriptedFetch(List.of(ok(EMBED_OK))))
				.fetchComments("DYtaeT4TPYu", "owner", 1);

		assertThat(result.complete()).isTrue();
		assertThat(result.comments()).hasSize(1);
		assertThat(result.comments().get(0).id()).isEqualTo("c1");
	}

	@Test
	void fetchComments_4인자는_3인자와_동일하게_위임한다() {
		CommentsFetch result = backend(new ScriptedFetch(List.of(ok(EMBED_OK))))
				.fetchComments("DYtaeT4TPYu", "owner", 1, Set.of("known"));

		assertThat(result.comments()).hasSize(1);
	}

	@Test
	void 미지원_경로_5종은_UnsupportedOperationException을_던진다() {
		SelfCrawlBackend backend = backend(new ScriptedFetch(List.of(ok(EMBED_OK))));

		assertThatThrownBy(() -> backend.fetchAuthorProfile("42"))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> backend.fetchTaggedPage("42", null))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> backend.fetchHashtagRecentPage("tag", null))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> backend.fetchClipCounts("42", 1))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> backend.resolveMediaByUrl("https://ig/share/x"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void 회복가능_401_2회_후_성공하면_재시도로_결과를_돌려준다() {
		ScriptedFetch embedFetch = new ScriptedFetch(List.of(
				throwing(SelfErrorClass.RECOVERABLE_401),
				throwing(SelfErrorClass.RECOVERABLE_401),
				ok(EMBED_OK)));

		PostInfo post = backend(embedFetch).fetchPost("SHORT");

		assertThat(post.username()).isEqualTo("nasa");
		assertThat(embedFetch.calls).isEqualTo(3);
	}

	@Test
	void 재시도_소진이_반복되면_표면_서킷이_트립해_이후_즉시_OTHER로_거른다() {
		// 항상 401 — fetchPost 1회 = 재시도 3회 소진 + 블록 1회 기록.
		ScriptedFetch embedFetch =
				new ScriptedFetch(List.of(throwing(SelfErrorClass.RECOVERABLE_401)));
		SelfCrawlBackend backend = backend(embedFetch);

		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> backend.fetchPost("SHORT"))
					.isInstanceOf(SelfCrawlException.class)
					.extracting(e -> ((SelfCrawlException) e).errorClass())
					.isEqualTo(SelfErrorClass.RECOVERABLE_401);
		}
		assertThat(embedFetch.calls).isEqualTo(15);

		// 블록 5회 = 임계값 도달 — 6번째는 fetcher를 건드리지 않고 가드에서 OTHER.
		assertThatThrownBy(() -> backend.fetchPost("SHORT"))
				.isInstanceOf(SelfCrawlException.class)
				.extracting(e -> ((SelfCrawlException) e).errorClass())
				.isEqualTo(SelfErrorClass.OTHER);
		assertThat(embedFetch.calls).isEqualTo(15);
	}

	@Test
	void NOT_FOUND는_재시도_없이_전파되고_서킷을_트립시키지_않는다() {
		ScriptedFetch embedFetch = new ScriptedFetch(List.of(
				throwing(SelfErrorClass.NOT_FOUND),
				throwing(SelfErrorClass.NOT_FOUND),
				throwing(SelfErrorClass.NOT_FOUND),
				throwing(SelfErrorClass.NOT_FOUND),
				throwing(SelfErrorClass.NOT_FOUND),
				ok(EMBED_OK)));
		SelfCrawlBackend backend = backend(embedFetch);

		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> backend.fetchPost("SHORT"))
					.isInstanceOf(SelfCrawlException.class)
					.extracting(e -> ((SelfCrawlException) e).errorClass())
					.isEqualTo(SelfErrorClass.NOT_FOUND);
		}
		// 재시도 없음: 5회 호출 = fetcher 5콜. 서킷도 안 트립 — 6번째는 fetcher까지 가서 성공한다.
		assertThat(embedFetch.calls).isEqualTo(5);
		assertThat(backend.fetchPost("SHORT").username()).isEqualTo("nasa");
	}
}
