package com.celfit.instagram.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HikerBackendHashtagTest {

	private final List<String> calls = new ArrayList<>();

	private HikerBackend client(String body) {
		return new HikerBackend(path -> {
			calls.add(path);
			return body;
		});
	}

	/** HikerBackendTest와 동일 관용구 — /hiker/ 클래스패스 리소스를 문자열로 읽는다. */
	private static String fixture(String name) {
		try (var in = HikerBackendHashtagTest.class.getResourceAsStream("/hiker/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String sectionsBody(String nextPageId, String... medias) {
		String items = String.join(",", medias);
		String cursor = nextPageId == null ? "null" : "\"" + nextPageId + "\"";
		return """
				{"response":{"sections":[{"layout_content":{"medias":[%s]}}],
				 "more_available":%s},"next_page_id":%s}"""
				.formatted(items, nextPageId != null, cursor);
	}

	private static String media(String code, long takenAt, String username, String taggedUser) {
		String usertags = taggedUser == null ? "{\"in\":[]}"
				: "{\"in\":[{\"user\":{\"username\":\"" + taggedUser + "\"}}]}";
		return """
				{"media":{"code":"%s","taken_at":%d,"media_type":1,
				 "caption":{"text":"캡션 #끌리메"},
				 "user":{"username":"%s","pk":"111"},
				 "like_count":10,"comment_count":2,"usertags":%s}}"""
				.formatted(code, takenAt, username, usertags);
	}

	@Test
	void 섹션_셰이프에서_게시물과_커서를_파싱한다() {
		HashtagPage page = client(sectionsBody("p2",
				media("AAA", 1786000000L, "poster1", null),
				media("BBB", 1786000001L, "poster2", "cclime_official")))
				.fetchHashtagRecentPage("끌리메", null);

		assertThat(calls).containsExactly("/v2/hashtag/medias/recent?name=%EB%81%8C%EB%A6%AC%EB%A9%94");
		assertThat(page.posts()).hasSize(2);
		assertThat(page.posts().get(0).post().shortCode()).isEqualTo("AAA");
		assertThat(page.posts().get(0).post().username()).isEqualTo("poster1");
		assertThat(page.posts().get(0).post().likes()).isEqualTo(10L);
		assertThat(page.posts().get(0).taggedUsernames()).isEmpty();
		assertThat(page.posts().get(1).taggedUsernames()).containsExactly("cclime_official");
		assertThat(page.nextPageId()).isEqualTo("p2");
	}

	@Test
	void 커서를_넘기면_page_id_파라미터가_붙는다() {
		client(sectionsBody(null)).fetchHashtagRecentPage("cclime", "p2");
		assertThat(calls).containsExactly("/v2/hashtag/medias/recent?name=cclime&page_id=p2");
	}

	@Test
	void 마지막_페이지는_커서가_null이다() {
		HashtagPage page = client(sectionsBody(null,
				media("AAA", 1786000000L, "poster1", null)))
				.fetchHashtagRecentPage("cclime", null);
		assertThat(page.nextPageId()).isNull();
	}

	@Test
	void 태그_404는_빈_페이지로_접는다() {
		HikerBackend client = new HikerBackend(path -> {
			throw new SubjectNotFoundException("Entries not found");
		});
		HashtagPage page = client.fetchHashtagRecentPage("없는태그", null);
		assertThat(page.posts()).isEmpty();
		assertThat(page.nextPageId()).isNull();
	}

	@Test
	void usertags의_username은_소문자로_정규화한다() {
		HashtagPage page = client(sectionsBody(null,
				media("AAA", 1786000000L, "poster1", "CClime_Official")))
				.fetchHashtagRecentPage("cclime", null);
		assertThat(page.posts().get(0).taggedUsernames()).containsExactly("cclime_official");
	}

	/**
	 * 실측 원본(PoC 2026-08-11, 06-recent-끌리메-p1.json) 앞 2섹션(6건)을 축약 체크인한 fixture.
	 * 봉투(sections/layout_content/medias/media, top-level next_page_id, response.more_available)는
	 * 원형 유지 — 실셰이프 회귀를 이 리소스 하나로 계속 잡는다.
	 */
	@Test
	void 실측_원본_셰이프_축약본을_파싱한다() {
		HashtagPage page = client(fixture("hashtag-recent.json"))
				.fetchHashtagRecentPage("끌리메", null);

		assertThat(page.posts()).extracting(hp -> hp.post().shortCode())
				.containsExactly("DbcVklphE-A", "DaxFOSOBuIF", "DbK-DgqAVoH",
						"DbpZ83xEemI", "DbPQm7lCRiM", "Da7A5u1hfGM");
		assertThat(page.posts().get(0).taggedUsernames()).isEmpty();
		assertThat(page.posts().get(1).taggedUsernames()).isEmpty();
		assertThat(page.posts().get(2).taggedUsernames()).containsExactly("cclime_rara");
		assertThat(page.posts().get(3).taggedUsernames()).containsExactly("cclime_official", "cclime_rara");
		assertThat(page.posts().get(4).taggedUsernames()).containsExactly("cclime_rara");
		assertThat(page.posts().get(5).taggedUsernames()).isEmpty();
		assertThat(page.nextPageId()).isEqualTo(
				"WyJRVkZEZFZsMmJVZG1RbUZFY2pseFNWUlVPRFpLUXpSRGFtOVdZMWRLYUhwM1pIQXhSRFpwUm5adk5EVmZZVlI1ZVRSc"
						+ "2FGTlRUelpOUzFOWVRXMDFUWE14YldsaWRXRmxaR2QwU1ZOaU1sVXpRMFl6Ykc5aFJnPT0iLFtdLDFd");
	}

	@Test
	void items_평탄_셰이프_폴백에서도_파싱한다() {
		String body = "{\"response\":{\"items\":[" + media("CCC", 1786000002L, "poster3", "cclime_official")
				+ "],\"more_available\":false}}";

		HashtagPage page = client(body).fetchHashtagRecentPage("cclime", null);

		assertThat(page.posts()).hasSize(1);
		assertThat(page.posts().get(0).post().shortCode()).isEqualTo("CCC");
		assertThat(page.posts().get(0).taggedUsernames()).containsExactly("cclime_official");
	}
}
