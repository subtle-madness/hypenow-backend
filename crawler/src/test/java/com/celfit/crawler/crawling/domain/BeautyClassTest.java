package com.celfit.crawler.crawling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BeautyClassTest {

    @Test
    void 파생_boolean_규칙_인플루언서와_회사만_beauty_true() {
        assertThat(BeautyClass.INFLUENCER.beauty()).isTrue();
        assertThat(BeautyClass.INFLUENCER.company()).isFalse();
        assertThat(BeautyClass.COMPANY.beauty()).isTrue();
        assertThat(BeautyClass.COMPANY.company()).isTrue();
        assertThat(BeautyClass.BEAUTY_SERVICE.beauty()).isFalse();
        assertThat(BeautyClass.BEAUTY_SERVICE.company()).isFalse();
        assertThat(BeautyClass.FOREIGN_INFLUENCER.beauty()).isFalse();
        assertThat(BeautyClass.FOREIGN_INFLUENCER.company()).isFalse();
        assertThat(BeautyClass.NOT_BEAUTY.beauty()).isFalse();
        assertThat(BeautyClass.NOT_BEAUTY.company()).isFalse();
    }

    @Test
    void classify는_beauty_class와_파생값을_함께_세팅하고_judgedAt은_건드리지_않는다() {
        Influencer inf = new Influencer("a");
        inf.classify(BeautyClass.BEAUTY_SERVICE, Influencer.BEAUTY_SOURCE_CLAUDE, "피부과 시술 홍보");

        assertThat(inf.getBeautyClass()).isEqualTo(BeautyClass.BEAUTY_SERVICE);
        assertThat(inf.getBeauty()).isFalse();
        assertThat(inf.getBeautyCompany()).isFalse();
        assertThat(inf.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
        assertThat(inf.getBeautyReason()).isEqualTo("피부과 시술 홍보");
        assertThat(inf.getBeautyJudgedAt()).isNull();
    }
}
