package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HandshakeExtractorTest {

    @Test
    void shortCode를_media_id로_디코딩한다() {
        // 스파이크 검증: DYtaeT4TPYu -> 3903892884139341358
        assertThat(HandshakeExtractor.mediaIdFromShortCode("DYtaeT4TPYu"))
                .isEqualTo(3903892884139341358L);
    }

    @Test
    void 페이지_HTML에서_lsd_토큰을_추출한다() throws Exception {
        String html = new String(getClass().getResourceAsStream("/instagram/post-page.html").readAllBytes());
        String lsd = HandshakeExtractor.lsdFrom(html);
        assertThat(lsd).isNotBlank();
        assertThat(lsd).doesNotContain("\"");   // 토큰만, 따옴표 없음
    }
}
