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
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("postUrl", postUrl);
            c.put("ownerUsername", node.path("user").path("username").asString());
            c.put("text", node.path("text").asString());
            // created_at = epoch seconds → ISO-8601 UTC (액터 경로의 ISO 문자열과 형식 일치)
            c.put("timestamp", ISO.format(Instant.ofEpochSecond(node.path("created_at").asLong())));
            out.add(c);
        }
        JsonNode pi = conn.path("page_info");
        String endCursor = pi.path("end_cursor").isNull() ? null : pi.path("end_cursor").asString(null);
        return new Page(out, endCursor, pi.path("has_next_page").asBoolean(false));
    }
}
