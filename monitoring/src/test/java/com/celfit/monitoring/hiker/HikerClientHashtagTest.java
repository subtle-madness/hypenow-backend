package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HikerClientHashtagTest {

	private final List<String> calls = new ArrayList<>();

	private HikerClient client(String body) {
		return new HikerClient(path -> {
			calls.add(path);
			return body;
		});
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
		HikerClient.HashtagPage page = client(sectionsBody("p2",
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
		HikerClient.HashtagPage page = client(sectionsBody(null,
				media("AAA", 1786000000L, "poster1", null)))
				.fetchHashtagRecentPage("cclime", null);
		assertThat(page.nextPageId()).isNull();
	}

	@Test
	void 태그_404는_빈_페이지로_접는다() {
		HikerClient client = new HikerClient(path -> {
			throw new SubjectNotFoundException("Entries not found");
		});
		HikerClient.HashtagPage page = client.fetchHashtagRecentPage("없는태그", null);
		assertThat(page.posts()).isEmpty();
		assertThat(page.nextPageId()).isNull();
	}

	@Test
	void usertags의_username은_소문자로_정규화한다() {
		HikerClient.HashtagPage page = client(sectionsBody(null,
				media("AAA", 1786000000L, "poster1", "CClime_Official")))
				.fetchHashtagRecentPage("cclime", null);
		assertThat(page.posts().get(0).taggedUsernames()).containsExactly("cclime_official");
	}

	@Test
	void 실측_원본_셰이프를_파싱한다() throws Exception {
		java.nio.file.Path p = java.nio.file.Path.of(
				"/private/tmp/claude-501/-Users-woomin-Project-hypenow-backend/e5953728-c84c-4279-a4d8-0e0cd62f60ec/scratchpad/poc-clime/06-recent-끌리메-p1.json");
		org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(p));   // CI엔 없음 — 로컬 전용 스모크
		HikerClient.HashtagPage page = client(java.nio.file.Files.readString(p))
				.fetchHashtagRecentPage("끌리메", null);
		assertThat(page.posts()).hasSizeGreaterThan(20);   // 실측 27건
		assertThat(page.posts().stream().filter(hp -> !hp.taggedUsernames().isEmpty())).isNotEmpty();
	}
}
