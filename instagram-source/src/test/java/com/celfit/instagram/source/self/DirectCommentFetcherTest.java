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

/**
 * 자체 댓글 fetcher — 1페이지는 부트스트랩 GET(SSR HTML)에서, 2페이지부터는 GraphQL 커서
 * 페이지네이션으로 얻는다. 커밋된 실 응답 픽스처(post_page_comments.html=SSR 1페이지 15건,
 * comments_response.json=GraphQL 페이지 15건) 기준 정확값 검증.
 */
class DirectCommentFetcherTest {

	private static String fixture(String name) {
		try (var in = DirectCommentFetcherTest.class.getResourceAsStream("/self/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 1페이지짜리(hasNext=false) SSR 픽스처를 hasNext=true+커서로 바꾼다. */
	private static String paged(String html, String cursor) {
		return html.replace("\"end_cursor\":null,\"has_next_page\":false",
				"\"end_cursor\":\"" + cursor + "\",\"has_next_page\":true");
	}

	/** get은 게시물 페이지(LSD+CSRF 쿠키+1페이지 댓글 SSR), post는 GraphQL 댓글 응답을 차례로 돌려주는 fake 전송. */
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

	/** SSR 부트스트랩 GET 응답 — Set-Cookie로 csrftoken을 함께 실어 보낸다(실측 헤더 계약). */
	private static SelfResponse okWithCsrf(String body, String csrfToken) {
		return new SelfResponse(200, body,
				Map.of("set-cookie", List.of("csrftoken=" + csrfToken + "; Path=/; Secure",
						"datr=xxx; Path=/")));
	}

	private static DirectCommentFetcher fetcher(SelfTransport transport) {
		return new DirectCommentFetcher(transport, () -> "DOC", () -> "FRIENDLY");
	}

	@Test
	void 단일_페이지_15건을_SSR_HTML에서_정확값으로_파싱한다() {
		FakeTransport fake = new FakeTransport(okWithCsrf(fixture("post_page_comments.html"), "CSRF1"));
		CommentsFetch result = fetcher(fake).fetch("DYtaeT4TPYu", "someowner", 1);

		assertThat(result.complete()).isTrue();
		assertThat(result.comments()).hasSize(15);
		assertThat(fake.postCalls).isZero();   // 1페이지만 요청하면 추가 POST가 전혀 없다.
		CommentInfo first = result.comments().get(0);
		assertThat(first.id()).isEqualTo("18108559372934377");
		assertThat(first.author()).isEqualTo("songsariiiii");
		assertThat(first.body()).isEqualTo("이정도는 기본아잉교 ❤️");
		assertThat(first.likeCount()).isEqualTo(1L);
		assertThat(first.commentedAt()).isEqualTo(Instant.ofEpochSecond(1779661498L));
		assertThat(first.ownerReplyText()).isNull();
	}

	@Test
	void graphql_바디에_lsd_doc_id_media_id와_X_CSRFToken_헤더가_실린다() {
		// SSR 1페이지가 hasNext=true라야 2페이지 POST가 나간다.
		String ssrPaged = paged(fixture("post_page_comments.html"), "SSR_CURSOR");
		FakeTransport fake = new FakeTransport(okWithCsrf(ssrPaged, "CSRFVALUE"),
				ok(fixture("comments_response.json")));
		fetcher(fake).fetch("DYtaeT4TPYu", "someowner", 2);

		assertThat(fake.postBodies).hasSize(1);
		String body = fake.postBodies.get(0);
		assertThat(body).contains("lsd=AdTzcKuUhG5YtLSOMQnpsn-LIEs");
		assertThat(body).contains("doc_id=DOC");
		assertThat(body).contains("fb_api_req_friendly_name=FRIENDLY");
		// variables는 JSON 직렬화 후 URL 인코딩 — media_id는 문자열 값, after는 SSR 1페이지의 커서.
		assertThat(body).contains("3903892884139341358");
		assertThat(body).contains("SSR_CURSOR");
		Map<String, String> headers = fake.postHeaders.get(0);
		assertThat(headers).containsEntry("x-fb-lsd", "AdTzcKuUhG5YtLSOMQnpsn-LIEs");
		assertThat(headers).containsEntry("x-ig-app-id", "936619743392459");
		assertThat(headers).containsEntry("Sec-Fetch-Site", "same-origin");
		assertThat(headers).containsEntry("X-CSRFToken", "CSRFVALUE");
	}

	@Test
	void csrf_쿠키가_없으면_X_CSRFToken_헤더_없이_시도한다() {
		String ssrPaged = paged(fixture("post_page_comments.html"), "SSR_CURSOR");
		FakeTransport fake = new FakeTransport(ok(ssrPaged), ok(fixture("comments_response.json")));
		fetcher(fake).fetch("DYtaeT4TPYu", "someowner", 2);

		assertThat(fake.postHeaders.get(0)).doesNotContainKey("X-CSRFToken");
	}

	@Test
	void 두번째_페이지로_전진하고_커서_미전진에서_멈춘다() {
		// SSR 1페이지(커서 SSR_CURSOR) → POST 1페이지(커서 CUR, 새 커서라 전진) →
		// POST 2페이지(같은 픽스처 재사용 → 커서 CUR 그대로 → 무진행 가드로 정지).
		String ssrPaged = paged(fixture("post_page_comments.html"), "SSR_CURSOR");
		String graphqlPaged = paged(fixture("comments_response.json"), "CUR");
		FakeTransport fake = new FakeTransport(okWithCsrf(ssrPaged, "CSRF1"), ok(graphqlPaged));
		CommentsFetch result = fetcher(fake).fetch("DYtaeT4TPYu", "someowner", 5);

		assertThat(fake.postCalls).isEqualTo(2);
		assertThat(result.complete()).isTrue();
		assertThat(result.comments()).hasSize(45);   // SSR 15 + POST 15 + POST 15(같은 커서에서 정지)
		assertThat(fake.postBodies.get(0)).contains("SSR_CURSOR");
		assertThat(fake.postBodies.get(1)).contains("CUR");
	}

	@Test
	void 부트스트랩_비200은_상태_분류_예외() {
		FakeTransport fake = new FakeTransport(new SelfResponse(429, ""));
		assertThatThrownBy(() -> fetcher(fake).fetch("DYtaeT4TPYu", "someowner", 1))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.RATE_LIMIT_429));
	}

	@Test
	void SSR_HTML에_comments_connection이_없으면_LOGIN_WALL_예외() {
		// LSD는 있지만 comments_connection이 없는 셸(로그인 벽 의심) — post_page_lsd.html 그대로 재사용.
		FakeTransport fake = new FakeTransport(ok(fixture("post_page_lsd.html")));
		assertThatThrownBy(() -> fetcher(fake).fetch("DYtaeT4TPYu", "someowner", 1))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.LOGIN_WALL));
	}

	@Test
	void graphql_200_로그인벽_HTML은_LOGIN_WALL_예외() {
		String ssrPaged = paged(fixture("post_page_comments.html"), "SSR_CURSOR");
		FakeTransport fake = new FakeTransport(okWithCsrf(ssrPaged, "CSRF1"),
				ok("<!DOCTYPE html><html><body>login</body></html>"));
		CommentsFetch result = fetcher(fake).fetch("DYtaeT4TPYu", "someowner", 2);

		// 중간 페이지(2페이지) 실패는 예외가 아니라 1페이지분을 보존한 부분 결과다.
		assertThat(result.complete()).isFalse();
		assertThat(result.comments()).hasSize(15);
	}

	@Test
	void graphql_200_비JSON은_잭슨_예외가_아닌_부분_결과() {
		// 파스 실패가 unchecked Jackson 예외로 새면 폴백망(Failover 라우팅)을 우회한다.
		String ssrPaged = paged(fixture("post_page_comments.html"), "SSR_CURSOR");
		FakeTransport fake = new FakeTransport(okWithCsrf(ssrPaged, "CSRF1"), ok("not json {{{"));
		CommentsFetch result = fetcher(fake).fetch("DYtaeT4TPYu", "someowner", 2);

		assertThat(result.complete()).isFalse();
		assertThat(result.comments()).hasSize(15);
	}

	@Test
	void 중간_페이지_비200은_1페이지분을_보존한_부분_결과() {
		String ssrPaged = paged(fixture("post_page_comments.html"), "SSR_CURSOR");
		FakeTransport fake = new FakeTransport(okWithCsrf(ssrPaged, "CSRF1"), new SelfResponse(500, ""));
		CommentsFetch partial = fetcher(fake).fetch("DYtaeT4TPYu", "someowner", 5);

		assertThat(partial.complete()).isFalse();
		assertThat(partial.comments()).hasSize(15);
	}

	@Test
	void doc_id_만료_errors_배열_응답은_1페이지분을_보존한_부분_결과() {
		// 200 + 유효 JSON이지만 data=null + errors=[{code:1675002}](doc_id 만료). 파싱은 성공하므로
		// 기존 잭슨-예외 분기(103행)에 안 걸린다 — complete=true 가짜 완주를 별도로 막아야 한다.
		String ssrPaged = paged(fixture("post_page_comments.html"), "SSR_CURSOR");
		FakeTransport fake = new FakeTransport(okWithCsrf(ssrPaged, "CSRF1"),
				ok(fixture("graphql_doc_id_expired_errors.json")));
		CommentsFetch partial = fetcher(fake).fetch("DYtaeT4TPYu", "someowner", 5);

		assertThat(partial.complete()).isFalse();
		assertThat(partial.comments()).hasSize(15);
	}

	@Test
	void data_xig_polaris_media가_null인_응답은_1페이지분을_보존한_부분_결과() {
		// 200 + errors 없이 data.xig_polaris_media만 null인 변형 — comments_connection 체인이
		// MissingNode로 흘러 빈 페이지(edges=0)로 오독되면 그대로 complete=true가 나가버린다.
		String ssrPaged = paged(fixture("post_page_comments.html"), "SSR_CURSOR");
		FakeTransport fake = new FakeTransport(okWithCsrf(ssrPaged, "CSRF1"),
				ok(fixture("graphql_media_null.json")));
		CommentsFetch partial = fetcher(fake).fetch("DYtaeT4TPYu", "someowner", 5);

		assertThat(partial.complete()).isFalse();
		assertThat(partial.comments()).hasSize(15);
	}
}
