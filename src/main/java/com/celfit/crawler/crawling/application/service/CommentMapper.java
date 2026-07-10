package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 인스타그램 댓글 GraphQL 응답을 스키마 호환 댓글 맵으로 변환하는 순수 매퍼. */
@Component
public class CommentMapper {

    public record Page(List<Map<String, Object>> comments, String endCursor, boolean hasNext) {}

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper om;

    public CommentMapper(ObjectMapper om) {
        this.om = om; // Boot이 구성한 tools.jackson ObjectMapper 빈 주입
    }

    public Page parse(String json, String postUrl) {
        JsonNode root;
        try {
            root = om.readTree(json);
        } catch (JacksonException e) {
            throw new ApifyException("댓글 응답 파싱 실패: " + e.getMessage(), e);
        }
        // RECON.md 실측 경로: data.xig_polaris_media.comments_connection
        JsonNode conn = root.path("data").path("xig_polaris_media").path("comments_connection");
        JsonNode edges = conn.path("edges");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode edge : edges) {
            JsonNode node = edge.path("node");
            JsonNode u = node.path("user");
            String id = node.path("id").asString();
            String username = u.path("username").asString();
            String profilePic = u.path("profile_pic_url").asString();

            // 액터(apify~instagram-comment-scraper)의 중첩 owner 객체와 동일 키.
            // 자체 GraphQL 응답에 없는 필드(fbid_v2·full_name 등)는 액터도 비로그인이라
            // 대부분 null이므로 null로 채워 payload 스키마를 완전히 일치시킨다.
            Map<String, Object> owner = new LinkedHashMap<>();
            owner.put("id", u.path("id").asString());
            owner.put("fbid_v2", null);           // 자체 응답에 없음 — 액터와 유일한 실질 차이
            owner.put("username", username);
            owner.put("full_name", null);
            owner.put("is_private", null);
            owner.put("is_verified", u.path("is_verified").asBoolean(false));
            owner.put("is_mentionable", null);
            owner.put("profile_pic_id", null);
            owner.put("profile_pic_url", profilePic);
            owner.put("latest_reel_media", null);

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", id);
            c.put("text", node.path("text").asString());
            c.put("owner", owner);
            c.put("postUrl", postUrl);
            c.put("replies", null);               // 비로그인은 답글 미노출 → 액터도 null
            // created_at = epoch seconds → ISO-8601 UTC (액터 경로의 ISO 문자열과 형식 일치)
            c.put("timestamp", ISO.format(Instant.ofEpochSecond(node.path("created_at").asLong())));
            c.put("commentUrl", commentUrl(postUrl, id));
            c.put("likesCount", node.path("comment_like_count").isNumber()
                    ? node.path("comment_like_count").asInt() : null);
            c.put("repliesCount", node.path("child_comment_count").isNumber()
                    ? node.path("child_comment_count").asInt() : null);
            c.put("ownerUsername", username);
            c.put("ownerProfilePicUrl", profilePic);
            out.add(c);
        }
        JsonNode pi = conn.path("page_info");
        String endCursor = pi.path("end_cursor").isNull() ? null : pi.path("end_cursor").asString(null);
        return new Page(out, endCursor, pi.path("has_next_page").asBoolean(false));
    }

    /** 액터의 commentUrl 형식과 동일: {postUrl}/c/{id} (postUrl 끝 슬래시 보정). */
    private static String commentUrl(String postUrl, String id) {
        String base = postUrl.endsWith("/") ? postUrl : postUrl + "/";
        return base + "c/" + id;
    }
}
