package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.PostInfo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** embed 단건 파서 — 커밋된 실응답 픽스처(en-US) 기준 정확값 검증. */
class EmbedPostFetcherTest {

	private static String fixture(String name) {
		try (var in = EmbedPostFetcherTest.class.getResourceAsStream("/self/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static EmbedPostFetcher fetcher(String body, int status) {
		return new EmbedPostFetcher((url, tier, headers) -> new SelfResponse(status, body));
	}

	@Test
	void 이미지_게시물_정확_지표를_파싱한다() {
		PostInfo p = fetcher(fixture("embed_image_en.html"), 200).fetch("DcOX3hWFiey");
		assertThat(p.shortCode()).isEqualTo("DcOX3hWFiey");
		assertThat(p.likes()).isEqualTo(485_263L);
		assertThat(p.comments()).isEqualTo(3_359L);
		assertThat(p.views()).isNull();
		assertThat(p.username()).isEqualTo("nasa");
		assertThat(p.contentType()).isEqualTo("FEED");
		assertThat(p.caption()).contains("With your powers combined");
		assertThat(p.viewsTrusted()).isFalse();
		assertThat(p.likesHidden()).isFalse();
	}

	@Test
	void 릴스_영상_조회수까지_파싱한다() {
		PostInfo p = fetcher(fixture("embed_reel_en.html"), 200).fetch("DcMXl1IPNtB");
		assertThat(p.likes()).isEqualTo(95_971L);
		assertThat(p.comments()).isEqualTo(789L);
		assertThat(p.views()).isEqualTo(560_365L);
		assertThat(p.username()).isEqualTo("nasajohnson");
		assertThat(p.contentType()).isEqualTo("REELS");
		assertThat(p.caption()).contains("Soothing spacewalk");
		assertThat(p.viewsTrusted()).isTrue();
	}

	@Test
	void 삭제_리다이렉트는_NOT_FOUND() {
		assertThatThrownBy(() -> fetcher("", 302).fetch("Bt_A-8dgHKW"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.NOT_FOUND));
	}
}
