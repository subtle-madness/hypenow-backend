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

/** HikerAPI /v2/user/suggested/profiles?expand_suggestion=true → item에 relatedProfiles 병합. */
@Component
public class HikerSuggestedSupplement {

    private final HikerHttp http;
    private final ObjectMapper om;

    public HikerSuggestedSupplement(HikerHttp http, ObjectMapper om) {
        this.http = http;
        this.om = om;
    }

    public void enrich(Map<String, Object> item) {
        Object uid = item.get("userId");
        if (uid == null) return;
        String body = http.get("/v2/user/suggested/profiles?user_id=" + uid + "&expand_suggestion=true");
        List<Map<String, Object>> related = new ArrayList<>();
        collectUsers(read(body), related);
        item.put("relatedProfiles", related);
    }

    private void collectUsers(JsonNode node, List<Map<String, Object>> acc) {
        if (node.isObject() && node.has("username") && (node.has("pk") || node.has("id"))) {
            Map<String, Object> u = new java.util.LinkedHashMap<>();
            u.put("username", node.path("username").asString(null));
            u.put("full_name", node.path("full_name").asString(null));
            u.put("is_verified", node.path("is_verified").asBoolean(false));
            acc.add(u);
            return;
        }
        for (JsonNode c : node) collectUsers(c, acc);
    }

    private JsonNode read(String json) {
        try { return om.readTree(json); }
        catch (JacksonException e) { throw new ApifyException("suggested 파싱 실패: " + e.getMessage(), e); }
    }
}
