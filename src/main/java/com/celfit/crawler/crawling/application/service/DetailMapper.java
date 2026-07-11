package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 소스별 상세 응답을 raw_post_detail payload 계약으로 정규화 + 원본 통째(_rawDetail). */
@Component
public class DetailMapper {

    private final ObjectMapper om;

    public DetailMapper(ObjectMapper om) {
        this.om = om;
    }

    /** HikerAPI /v2/media/info/by/code 미디어 객체. */
    public Map<String, Object> fromHikerMedia(String json) {
        JsonNode root = read(json);
        // HikerAPI /v2/media/info/by/code 는 미디어를 media_or_ad(또는 media)로 감싸 반환한다
        JsonNode m = root.has("media") ? root.path("media")
                : root.has("media_or_ad") ? root.path("media_or_ad")
                : root;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("shortCode", m.path("code").asString(null));
        p.put("caption", m.path("caption_text").asString(m.path("caption").path("text").asString(null)));
        p.put("likesCount", m.path("like_count").asLong());
        p.put("commentsCount", m.path("comment_count").asLong());
        p.put("videoPlayCount", m.path("play_count").asLong());
        p.put("isPaidPartnership", m.path("is_paid_partnership").asBoolean(false));
        p.put("_rawDetail", raw(root));
        return p;
    }

    /** self-crawl GraphQL 포스트 쿼리(data.xdt_shortcode_media). */
    public Map<String, Object> fromSelfGraphql(String json) {
        JsonNode root = read(json);
        JsonNode media = root.has("data") ? root.path("data").path("xdt_shortcode_media") : root;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("shortCode", media.path("shortcode").asString(null));
        p.put("caption", media.path("edge_media_to_caption").path("edges").path(0)
                .path("node").path("text").asString(null));
        p.put("likesCount", media.path("edge_media_preview_like").path("count").asLong());
        p.put("commentsCount", media.path("edge_media_to_comment").path("count").asLong());
        p.put("videoPlayCount", null);   // 피드=조회수 없음
        p.put("isPaidPartnership", media.path("is_paid_partnership").asBoolean(false));
        p.put("_rawDetail", raw(root));
        return p;
    }

    /** Apify 상세 액터 아이템 — 이미 하드계약 키(shortCode/caption/likesCount/…) 보유, 그대로 통과. */
    public Map<String, Object> fromActorItem(Map<String, Object> item) {
        return item;
    }

    private JsonNode read(String json) {
        try {
            return om.readTree(json);
        } catch (JacksonException e) {
            throw new ApifyException("상세 JSON 파싱 실패: " + e.getMessage(), e);
        }
    }

    private Object raw(JsonNode node) {
        return om.convertValue(node, Object.class);
    }
}
