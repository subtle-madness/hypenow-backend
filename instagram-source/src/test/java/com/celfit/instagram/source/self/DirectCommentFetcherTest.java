package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.CommentInfo;
import com.celfit.instagram.source.CommentsFetch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 자체 댓글 fetcher — 커밋된 실 GraphQL 응답 픽스처(15건 단일 페이지) 기준 정확값 검증. */
class DirectCommentFetcherTest {

	private static String fixture(String name) {
		try (var in = DirectCommentFetcherTest.class.getResourceAsStream("/self/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** get은 게시물 페이지(LSD), post는 GraphQL 댓글 응답을 차례로 돌려주는 fake 전송. */
	private static final class FakeTransport implements SelfTransport {
		final SelfResponse getResponse;
		final List<SelfResponse> postResponses;
		final List<String> postBodies = new ArrayList<>();
		final List<Map<String, String>> postHeaders = new ArrayList<>();
		int postCalls;

		FakeTransport(SelfResponse getResponse, SelfResponse... postResponses) {
			this.getResponse = getResponse;
			this.postResponses = List.of(postResponses);
		}

		@Override
		public SelfResponse get(String url, ProxyTier tier, Map<String, String> headers) {
			return getResponse;
		}

		@Override
		public SelfResponse post(String url, String formBody, ProxyTier tier,
				Map<String, String> headers) {
			postBodies.add(formBody);
			postHeaders.add(headers);
			return postResponses.get(Math.min(postCalls++, postResponses.size() - 1));
		}
	}

	private static SelfResponse ok(String body) {
		return new SelfResponse(200, body);
	}

	@Test
	void 단일_페이지_15건을_정확값으로_파싱한다() {
		FakeTransport fake = new FakeTransport(ok(fixture("post_page_lsd.html")),
				ok(fixture("comments_response.json")));
		CommentsFetch result = new DirectCommentFetcher(fake, "DOC", "FRIENDLY")
				.fetch("DYtaeT4TPYu", "someowner", 1);

		assertThat(result.complete()).isTrue();
		assertThat(result.comments()).hasSize(15);
		CommentInfo first = result.comments().get(0);
		assertThat(first.id()).isEqualTo("18108559372934377");
		assertThat(first.author()).isEqualTo("songsariiiii");
		assertThat(first.body()).isEqualTo("이정도는 기본아잉교 ❤️");
		assertThat(first.likeCount()).isEqualTo(1L);
		assertThat(first.commentedAt()).isEqualTo(Instant.ofEpochSecond(1779661498L));
		assertThat(first.ownerReplyText()).isNull();
	}

	@Test
	void graphql_바디에_lsd와_media_id_변수가_실린다() {
		FakeTransport fake = new FakeTransport(ok(fixture("post_page_lsd.html")),
				ok(fixture("comments_response.json")));
		new DirectCommentFetcher(fake, "DOC", "FRIENDLY").fetch("DYtaeT4TPYu", "someowner", 1);

		assertThat(fake.postBodies).hasSize(1);
		String body = fake.postBodies.get(0);
		assertThat(body).contains("lsd=AdTzcKuUhG5YtLSOMQnpsn-LIEs");
		assertThat(body).contains("doc_id=DOC");
		assertThat(body).contains("fb_api_req_friendly_name=FRIENDLY");
		// variables는 JSON 직렬화 후 URL 인코딩 — media_id는 문자열 값이다.
		assertThat(body).contains("3903892884139341358");
		Map<String, String> headers = fake.postHeaders.get(0);
		assertThat(headers).containsEntry("x-fb-lsd", "AdTzcKuUhG5YtLSOMQnpsn-LIEs");
		assertThat(headers).containsEntry("x-ig-app-id", "936619743392459");
		assertThat(headers).containsEntry("Sec-Fetch-Site", "same-origin");
	}

	@Test
	void 두번째_페이지로_전진하고_커서_미전진에서_멈춘다() {
		// 픽스처를 has_next_page=true + 커서 CUR로 바꿔 2페이지째를 유도 —
		// 2페이지도 같은 커서를 돌려주므로 미전진 가드가 3페이지 호출을 막아야 한다.
		String paged = fixture("comments_response.json")
				.replace("\"end_cursor\":null,\"has_next_page\":false",
						"\"end_cursor\":\"CUR\",\"has_next_page\":true");
		FakeTransport fake = new FakeTransport(ok(fixture("post_page_lsd.html")), ok(paged));
		CommentsFetch result = new DirectCommentFetcher(fake, "DOC", "FRIENDLY")
				.fetch("DYtaeT4TPYu", "someowner", 5);

		assertThat(fake.postCalls).isEqualTo(2);
		assertThat(result.complete()).isTrue();
		assertThat(result.comments()).hasSize(30);
		// 2페이지 요청 바디에 after 커서가 실렸는지 확인한다.
		assertThat(fake.postBodies.get(1)).contains("CUR");
	}

	@Test
	void 부트스트랩_비200은_상태_분류_예외() {
		FakeTransport fake = new FakeTransport(new SelfResponse(429, ""),
				ok(fixture("comments_response.json")));
		assertThatThrownBy(() -> new DirectCommentFetcher(fake, "DOC", "FRIENDLY")
				.fetch("DYtaeT4TPYu", "someowner", 1))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.RATE_LIMIT_429));
	}

	@Test
	void graphql_200_로그인벽_HTML은_LOGIN_WALL_예외() {
		FakeTransport fake = new FakeTransport(ok(fixture("post_page_lsd.html")),
				ok("<!DOCTYPE html><html><body>login</body></html>"));
		assertThatThrownBy(() -> new DirectCommentFetcher(fake, "DOC", "FRIENDLY")
				.fetch("DYtaeT4TPYu", "someowner", 1))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.LOGIN_WALL));
	}

	@Test
	void graphql_200_비JSON은_잭슨_예외가_아닌_SelfCrawlException() {
		// 파스 실패가 unchecked Jackson 예외로 새면 폴백망(Failover 라우팅)을 우회한다.
		FakeTransport fake = new FakeTransport(ok(fixture("post_page_lsd.html")),
				ok("not json {{{"));
		assertThatThrownBy(() -> new DirectCommentFetcher(fake, "DOC", "FRIENDLY")
				.fetch("DYtaeT4TPYu", "someowner", 1))
				.isInstanceOf(SelfCrawlException.class);
	}

	@Test
	void 첫_페이지_비200은_예외_중간_페이지_비200은_부분_결과() {
		FakeTransport failFirst = new FakeTransport(ok(fixture("post_page_lsd.html")),
				new SelfResponse(429, ""));
		assertThatThrownBy(() -> new DirectCommentFetcher(failFirst, "DOC", "FRIENDLY")
				.fetch("DYtaeT4TPYu", "someowner", 1))
				.isInstanceOf(SelfCrawlException.class);

		String paged = fixture("comments_response.json")
				.replace("\"end_cursor\":null,\"has_next_page\":false",
						"\"end_cursor\":\"CUR\",\"has_next_page\":true");
		FakeTransport failSecond = new FakeTransport(ok(fixture("post_page_lsd.html")),
				ok(paged), new SelfResponse(500, ""));
		CommentsFetch partial = new DirectCommentFetcher(failSecond, "DOC", "FRIENDLY")
				.fetch("DYtaeT4TPYu", "someowner", 5);
		assertThat(partial.complete()).isFalse();
		assertThat(partial.comments()).hasSize(15);
	}
}
