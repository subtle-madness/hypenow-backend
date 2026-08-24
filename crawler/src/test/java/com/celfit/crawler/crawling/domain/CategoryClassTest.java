package com.celfit.crawler.crawling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CategoryClassTest {

    @Test
    void 파생_boolean은_INFLUENCER와_COMPANY만_카테고리_소속이다() {
        assertThat(CategoryClass.INFLUENCER.inCategory()).isTrue();
        assertThat(CategoryClass.COMPANY.inCategory()).isTrue();
        assertThat(CategoryClass.SERVICE.inCategory()).isFalse();
        assertThat(CategoryClass.FOREIGN_INFLUENCER.inCategory()).isFalse();
        assertThat(CategoryClass.NONE.inCategory()).isFalse();
    }

    @Test
    void company는_COMPANY만_true다() {
        assertThat(CategoryClass.COMPANY.company()).isTrue();
        assertThat(CategoryClass.INFLUENCER.company()).isFalse();
        assertThat(CategoryClass.SERVICE.company()).isFalse();
    }
}
