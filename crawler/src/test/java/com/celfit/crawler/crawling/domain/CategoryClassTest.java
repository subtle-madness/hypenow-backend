package com.celfit.crawler.crawling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CategoryClassTest {

    @Test
    void classifyHomeLiving은_파생_boolean을_class와_일치시킨다() {
        Influencer inf = new Influencer("acc");
        inf.classifyHomeLiving(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "집꾸미기", "CAPTION");
        assertThat(inf.getHomeLiving()).isTrue();
        assertThat(inf.getHomeLivingCompany()).isFalse();
        assertThat(inf.getHomeLivingClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(inf.getHomeLivingSource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
        assertThat(inf.getHomeLivingReason()).isEqualTo("집꾸미기");
        assertThat(inf.getHomeLivingBasis()).isEqualTo("CAPTION");

        inf.classifyHomeLiving(CategoryClass.COMPANY, Influencer.BEAUTY_SOURCE_MANUAL, "가구 브랜드", null);
        assertThat(inf.getHomeLiving()).isTrue();
        assertThat(inf.getHomeLivingCompany()).isTrue();

        inf.classifyHomeLiving(CategoryClass.SERVICE, Influencer.BEAUTY_SOURCE_CLAUDE, "인테리어 시공", "BIO");
        assertThat(inf.getHomeLiving()).isFalse();
        assertThat(inf.getHomeLivingCompany()).isFalse();

        inf.classifyHomeLiving(CategoryClass.NONE, Influencer.BEAUTY_SOURCE_CLAUDE, "일상 계정", "CAPTION");
        assertThat(inf.getHomeLiving()).isFalse();
    }
}
