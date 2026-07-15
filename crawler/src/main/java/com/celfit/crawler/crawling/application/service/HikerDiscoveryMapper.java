package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HikerAPI /v2/hashtag/medias/top 응답을 DiscoveryItemParser 계약(payload)으로 정규화.
 * sections[].layout_content의 medias/fill_items/one_by_two_item.clips.items 3종을 순회하고
 * 필수(code·taken_at·user.username) 결손 노드(캐러셀 조각 등)는 스킵. 원본 media는 _rawMedia로 보존.
 */
@Component
public class HikerDiscoveryMapper {

    /** 한 페이지 결과: 정규화 아이템 + 다음 페이지 커서. */
    public record Page(List<Map<String, Object>> items, String nextPageId, boolean moreAvailable) {}

    private final ObjectMapper om;

    public HikerDiscoveryMapper(ObjectMapper om) {
        this.om = om;
    }

    public Page parse(String json) {
        JsonNode root = read(json);
        JsonNode response = root.path("response");
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode section : response.path("sections")) {
            JsonNode lc = section.path("layout_content");
            collect(lc.path("medias"), items);
            collect(lc.path("fill_items"), items);
            collect(lc.path("one_by_two_item").path("clips").path("items"), items);
        }
        return new Page(items,
                root.path("next_page_id").asString(null),
                response.path("more_available").asBoolean(false));
    }

    private void collect(JsonNode arr, List<Map<String, Object>> out) {
        for (JsonNode node : arr) {
            JsonNode m = node.has("media") ? node.path("media") : node;
            Map<String, Object> item = toItem(m);
            if (item != null) out.add(item);
        }
    }

    /** 파서 필수 3필드 결손이면 null (캐러셀 조각·비정상 노드). */
    private Map<String, Object> toItem(JsonNode m) {
        String code = m.path("code").asString(null);
        long takenAt = m.path("taken_at").asLong();
        String username = m.path("user").path("username").asString(null);
        if (code == null || takenAt <= 0 || username == null) return null;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("shortCode", code);
        item.put("timestamp", Instant.ofEpochSecond(takenAt).toString());
        item.put("ownerUsername", username);
        item.put("productType", m.path("product_type").asString(null));
        item.put("caption", m.path("caption").path("text").asString(null));
        item.put("likesCount", m.path("like_count").asLong());
        item.put("commentsCount", m.path("comment_count").asLong());
        item.put("videoPlayCount", m.path("play_count").asLong());
        item.put("_rawMedia", om.convertValue(m, Object.class));
        return item;
    }

    private JsonNode read(String json) {
        try {
            return om.readTree(json);
        } catch (JacksonException e) {
            throw new ApifyException("해시태그 응답 파싱 실패: " + e.getMessage(), e);
        }
    }
}
