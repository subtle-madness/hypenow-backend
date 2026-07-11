package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HikerAPI /v1/user/medias/chunk → item에 latestPosts(각 code·play_count·like_count 등) 병합. */
@Component
public class HikerMediasSupplement {

    private final HikerHttp http;
    private final ObjectMapper om;

    public HikerMediasSupplement(HikerHttp http, ObjectMapper om) {
        this.http = http;
        this.om = om;
    }

    public void enrich(Map<String, Object> item) {
        Object uid = item.get("userId");
        if (uid == null) return;
        String body = http.get("/v1/user/medias/chunk?user_id=" + uid);
        List<Map<String, Object>> posts = new ArrayList<>();
        JsonNode arr = firstArray(read(body));
        for (JsonNode n : arr) {
            JsonNode m = n.has("media") ? n.path("media") : n;
            Map<String, Object> post = new java.util.LinkedHashMap<>();
            post.put("shortCode", m.path("code").asString(null));
            post.put("videoViewCount", m.path("play_count").asLong());
            post.put("likesCount", m.path("like_count").asLong());
            post.put("commentsCount", m.path("comment_count").asLong());
            posts.add(post);
        }
        item.put("latestPosts", posts);
    }

    private JsonNode read(String json) {
        try { return om.readTree(json); }
        catch (JacksonException e) { throw new ApifyException("medias 파싱 실패: " + e.getMessage(), e); }
    }

    // 응답 구조가 {response:{items:[...]}} 또는 {items:[...]} 또는 [...] 등 다양 → 첫 번째 배열을 찾음
    private JsonNode firstArray(JsonNode node) {
        if (node.isArray()) return node;
        for (JsonNode child : node) {
            JsonNode found = firstArray(child);
            if (found.isArray()) return found;
        }
        return om.createArrayNode();
    }
}
