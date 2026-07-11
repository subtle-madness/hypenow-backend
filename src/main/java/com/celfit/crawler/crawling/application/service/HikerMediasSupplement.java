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
        JsonNode arr = mediaArray(read(body));
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

    // 응답 구조가 [[...medias...], "cursor"](실제 medias/chunk) / {response:{items:[...]}} / {items:[...]} / [...] 등 다양.
    // "첫 배열"을 집으면 chunk의 바깥 [배열,커서] 튜플을 잘못 순회하므로,
    // 미디어 객체(code/pk 보유)를 원소로 갖는 배열을 재귀 탐색해서 고른다.
    private JsonNode mediaArray(JsonNode node) {
        if (isMediaArray(node)) return node;
        for (JsonNode child : node) {
            JsonNode found = mediaArray(child);
            if (found.isArray() && !found.isEmpty()) return found;
        }
        return om.createArrayNode();
    }

    private boolean isMediaArray(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) return false;
        for (JsonNode el : node) {
            JsonNode m = el.has("media") ? el.path("media") : el;
            if (m.isObject() && (m.has("code") || m.has("pk"))) return true;
        }
        return false;
    }
}
