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

    /** suggested 응답 결과 — users는 user 노드 원형 전체, raw는 응답 트리 전체. */
    public record Suggested(List<Map<String, Object>> users, Object raw) {}

    /** SIMILAR 잡·related 보충 공용 — 호출 1회로 유사 user 노드 원형을 수집한다. */
    public Suggested fetch(String userId) {
        String body = http.get("/v2/user/suggested/profiles?user_id=" + userId + "&expand_suggestion=true");
        JsonNode root = read(body);
        List<Map<String, Object>> users = new ArrayList<>();
        collectUsers(root, users);
        return new Suggested(users, om.convertValue(root, Object.class));
    }

    public void enrich(Map<String, Object> item, String userId) {
        if (userId == null) return;
        Suggested s = fetch(userId);
        List<Map<String, Object>> related = new ArrayList<>();
        for (Map<String, Object> u : s.users()) {
            Map<String, Object> slim = new java.util.LinkedHashMap<>();
            slim.put("username", u.get("username"));
            slim.put("full_name", u.get("full_name"));
            slim.put("is_verified", u.get("is_verified") instanceof Boolean b && b);
            related.add(slim);
        }
        item.put("relatedProfiles", related);
        item.put("_rawSuggested", s.raw());
    }

    @SuppressWarnings("unchecked")
    private void collectUsers(JsonNode node, List<Map<String, Object>> acc) {
        if (node.isObject() && node.has("username") && (node.has("pk") || node.has("id"))) {
            acc.add(om.convertValue(node, Map.class));
            return;
        }
        for (JsonNode c : node) collectUsers(c, acc);
    }

    private JsonNode read(String json) {
        try { return om.readTree(json); }
        catch (JacksonException e) { throw new ApifyException("suggested 파싱 실패: " + e.getMessage(), e); }
    }
}
