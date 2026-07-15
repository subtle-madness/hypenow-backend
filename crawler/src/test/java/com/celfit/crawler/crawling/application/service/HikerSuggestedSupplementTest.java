package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HikerSuggestedSupplementTest {

    static final String BODY = """
            {"response":{"suggested_users":[
              {"username":"rel1","full_name":"이름1","is_verified":false,"pk":"1","biography":"bio1"},
              {"username":"rel2","full_name":"이름2","is_verified":true,"id":"2"}
            ]}}
            """;

    ObjectMapper om = new ObjectMapper();
    HikerSuggestedSupplement sut = new HikerSuggestedSupplement(path -> BODY, om);

    @Test
    void fetch는_user_노드_원형_전체를_수집한다() {
        var s = sut.fetch("123");
        assertThat(s.users()).hasSize(2);
        assertThat(s.users().get(0))
                .containsEntry("username", "rel1")
                .containsEntry("biography", "bio1");  // 슬림 3키가 아니라 원형 그대로
        assertThat(s.raw()).isNotNull();
    }

    @Test
    void enrich는_기존_계약대로_슬림_relatedProfiles와_rawSuggested를_병합한다() {
        Map<String, Object> item = new LinkedHashMap<>();
        sut.enrich(item, "123");
        @SuppressWarnings("unchecked")
        var related = (List<Map<String, Object>>) item.get("relatedProfiles");
        assertThat(related).hasSize(2);
        assertThat(related.get(0).keySet()).containsExactly("username", "full_name", "is_verified");
        assertThat(item).containsKey("_rawSuggested");
    }
}
