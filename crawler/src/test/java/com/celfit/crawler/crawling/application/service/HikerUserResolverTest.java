package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HikerUserResolverTest {

    ObjectMapper om = new ObjectMapper();

    @Test
    void user_래퍼가_있으면_그_안의_pk를_해석한다() {
        HikerUserResolver r = new HikerUserResolver(
                path -> "{\"user\":{\"username\":\"a\",\"pk\":12345}}", om);
        assertThat(r.resolvePk("a")).isEqualTo("12345");
    }

    @Test
    void 평탄_응답이면_최상위_pk를_해석하고_pk가_없으면_id_폴백() {
        HikerUserResolver flat = new HikerUserResolver(
                path -> "{\"username\":\"a\",\"pk\":\"77\"}", om);
        assertThat(flat.resolvePk("a")).isEqualTo("77");

        HikerUserResolver idOnly = new HikerUserResolver(
                path -> "{\"username\":\"a\",\"id\":\"88\"}", om);
        assertThat(idOnly.resolvePk("a")).isEqualTo("88");
    }

    @Test
    void pk도_id도_없으면_null() {
        HikerUserResolver r = new HikerUserResolver(path -> "{\"username\":\"a\"}", om);
        assertThat(r.resolvePk("a")).isNull();
    }
}
