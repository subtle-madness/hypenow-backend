package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 소스별 프로필 응답을 raw_profile payload 계약(username·followersCount·userId)으로 정규화. */
@Component
public class ProfileMapper {

    private final ObjectMapper om;

    public ProfileMapper(ObjectMapper om) {
        this.om = om; // Boot이 구성한 tools.jackson ObjectMapper 빈 주입
    }

    /** self-crawl web_profile_info(GraphQL) 단건. */
    public Map<String, Object> fromSelf(String json) {
        JsonNode user = read(json).path("data").path("user");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("username", user.path("username").asString(null));
        p.put("userId", user.path("id").asString(null));
        p.put("followersCount", user.path("edge_followed_by").path("count").asLong());
        p.put("followsCount", user.path("edge_follow").path("count").asLong());
        p.put("fullName", user.path("full_name").asString(null));
        p.put("biography", user.path("biography").asString(null));
        p.put("verified", user.path("is_verified").asBoolean(false));
        p.put("private", user.path("is_private").asBoolean(false));
        return p;
    }

    /** HikerAPI v2/user/by/username 또는 gql/web_profile_info(모바일 user 객체). */
    public Map<String, Object> fromHikerUser(String json) {
        JsonNode root = read(json);
        JsonNode user = root.has("user") ? root.path("user") : root;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("username", user.path("username").asString(null));
        p.put("userId", user.path("pk").asString(user.path("id").asString(null)));
        p.put("followersCount", user.path("follower_count").asLong());
        p.put("followsCount", user.path("following_count").asLong());
        p.put("fullName", user.path("full_name").asString(null));
        p.put("biography", user.path("biography").asString(null));
        p.put("verified", user.path("is_verified").asBoolean(false));
        p.put("private", user.path("is_private").asBoolean(false));
        return p;
    }

    /** Apify 프로필 액터 아이템 — 이미 username/followersCount 존재, userId 보강 + Long 정규화. */
    public Map<String, Object> fromActorItem(Map<String, Object> item) {
        Map<String, Object> p = new LinkedHashMap<>(item);
        p.put("username", item.get("username"));
        p.put("followersCount", toLong(item.get("followersCount")));
        Object uid = item.get("id");
        if (uid != null) p.put("userId", String.valueOf(uid));
        return p;
    }

    private JsonNode read(String json) {
        try {
            return om.readTree(json);
        } catch (JacksonException e) {
            throw new ApifyException("프로필 JSON 파싱 실패: " + e.getMessage(), e);
        }
    }

    private Long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) return Long.parseLong(s);
        return null;
    }
}
