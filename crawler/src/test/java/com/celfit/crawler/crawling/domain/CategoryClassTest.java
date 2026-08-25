package com.celfit.crawler.crawling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CategoryClassTest {

    @Test
    void 파생_boolean_규칙_인플루언서와_회사만_카테고리_소속() {
        assertThat(CategoryClass.INFLUENCER.inCategory()).isTrue();
        assertThat(CategoryClass.INFLUENCER.company()).isFalse();
        assertThat(CategoryClass.COMPANY.inCategory()).isTrue();
        assertThat(CategoryClass.COMPANY.company()).isTrue();
        assertThat(CategoryClass.SERVICE.inCategory()).isFalse();
        assertThat(CategoryClass.SERVICE.company()).isFalse();
        assertThat(CategoryClass.FOREIGN_INFLUENCER.inCategory()).isFalse();
        assertThat(CategoryClass.FOREIGN_INFLUENCER.company()).isFalse();
        assertThat(CategoryClass.NONE.inCategory()).isFalse();
        assertThat(CategoryClass.NONE.company()).isFalse();
    }

    @Test
    void classifyFnb는_fnb_class와_파생값을_함께_세팅하고_judgedAt은_건드리지_않는다() {
        Influencer inf = new Influencer("a");
        inf.classifyFnb(CategoryClass.SERVICE, Influencer.BEAUTY_SOURCE_CLAUDE, "카페 업장 공식 계정", "BIO");

        assertThat(inf.getFnbClass()).isEqualTo(CategoryClass.SERVICE);
        assertThat(inf.getFnb()).isFalse();
        assertThat(inf.getFnbCompany()).isFalse();
        assertThat(inf.getFnbSource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
        assertThat(inf.getFnbReason()).isEqualTo("카페 업장 공식 계정");
        assertThat(inf.getFnbBasis()).isEqualTo("BIO");
        assertThat(inf.getFnbJudgedAt()).isNull();
    }

    @Test
    void classifyFnb는_COMPANY에서_fnb와_fnb_company를_함께_켠다() {
        Influencer inf = new Influencer("b");
        inf.classifyFnb(CategoryClass.COMPANY, Influencer.BEAUTY_SOURCE_MANUAL, "식품 브랜드", "CATEGORY_ONLY");

        assertThat(inf.getFnb()).isTrue();
        assertThat(inf.getFnbCompany()).isTrue();
    }
}
