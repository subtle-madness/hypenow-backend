package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 핸드셰이크 추출 — 커밋된 실 게시물 페이지 픽스처 기준(LSD 토큰·shortcode→media pk 산술). */
class HandshakeExtractorTest {

	private static String fixture(String name) {
		try (var in = HandshakeExtractorTest.class.getResourceAsStream("/self/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Test
	void 게시물_페이지에서_lsd_토큰을_추출한다() {
		assertThat(HandshakeExtractor.lsdFrom(fixture("post_page_lsd.html")))
				.isEqualTo("AdTzcKuUhG5YtLSOMQnpsn-LIEs");
	}

	@Test
	void lsd_없는_본문은_로그인_벽으로_분류한다() {
		assertThatThrownBy(() -> HandshakeExtractor.lsdFrom("<html><body>login</body></html>"))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.LOGIN_WALL));
	}

	@Test
	void shortcode를_media_pk로_산술_변환한다() {
		assertThat(HandshakeExtractor.mediaIdFromShortCode("DYtaeT4TPYu"))
				.isEqualTo(3903892884139341358L);
	}

	@Test
	void 알파벳_밖_문자는_IllegalArgumentException() {
		assertThatThrownBy(() -> HandshakeExtractor.mediaIdFromShortCode("DYta!eT4"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
