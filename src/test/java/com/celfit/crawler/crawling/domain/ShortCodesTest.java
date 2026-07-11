package com.celfit.crawler.crawling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ShortCodesTest {

    @Test void shortCode를_media_pk로_디코드() {
        // 실측 쌍(HikerAPI 픽스처): code ↔ pk
        assertThat(ShortCodes.mediaId("DakcjkOuiZi")).isEqualTo("3937397563614439010");
    }

    @Test void 첫문자_A는_0() {
        assertThat(ShortCodes.mediaId("A")).isEqualTo("0");
    }
}
