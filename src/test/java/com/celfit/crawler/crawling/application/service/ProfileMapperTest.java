package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProfileMapperTest {

    ProfileMapper mapper = new ProfileMapper(new ObjectMapper());

    @Test void self_graphql_정규화() {
        String json = """
            {"data":{"user":{"username":"beauty.e.ze","id":"74851841915",
              "edge_followed_by":{"count":2369}}}}""";
        Map<String, Object> p = mapper.fromSelf(json);
        assertThat(p.get("username")).isEqualTo("beauty.e.ze");
        assertThat(p.get("followersCount")).isEqualTo(2369L);
        assertThat(p.get("userId")).isEqualTo("74851841915");
    }

    @Test void hiker_user_정규화() {
        String json = """
            {"user":{"username":"tem.duck","pk":"74756186520","follower_count":256559}}""";
        Map<String, Object> p = mapper.fromHikerUser(json);
        assertThat(p.get("username")).isEqualTo("tem.duck");
        assertThat(p.get("followersCount")).isEqualTo(256559L);
        assertThat(p.get("userId")).isEqualTo("74756186520");
    }

    @Test void actor_아이템_보강() {
        Map<String, Object> item = new java.util.HashMap<>(Map.of(
            "username", "tem.duck", "followersCount", 256169, "id", "74756186520"));
        Map<String, Object> p = mapper.fromActorItem(item);
        assertThat(p.get("username")).isEqualTo("tem.duck");
        assertThat(p.get("followersCount")).isEqualTo(256169L);
        assertThat(p.get("userId")).isEqualTo("74756186520");
    }
}
