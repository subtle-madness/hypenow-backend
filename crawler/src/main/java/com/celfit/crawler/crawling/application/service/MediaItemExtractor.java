package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.domain.RawSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 저장된 열거 페이지 원형에서 제어 필드만 추출. 원형은 이미 raw_media_page에 있으므로
 * 여기서의 결손·실패는 데이터 유실이 아니다(해당 아이템만 건너뜀).
 * Hiker gql flat은 숫자 필드에 1l/1f 접두사를 붙인다 — get()이 3형을 순서대로 조회.
 */
public final class MediaItemExtractor {

    public record MediaItem(String shortCode, Instant takenAt, ContentType type, boolean pinned) {}

    public static List<MediaItem> extract(Map<String, Object> payload, RawSource source) {
        List<MediaItem> out = new ArrayList<>();
        for (Object o : items(payload, source)) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> m = unwrapMedia(raw);
            String code = firstString(m.get("code"), m.get("shortcode"));   // SELF_GQL은 shortcode
            Long takenAt = asLong(get(m, "taken_at"));
            if (takenAt == null) takenAt = asLong(m.get("taken_at_timestamp")); // SELF_GQL
            if (code == null || takenAt == null) continue;
            ContentType type = "clips".equals(m.get("product_type"))
                    ? ContentType.REELS : ContentType.FEED;
            boolean pinned = nonEmptyList(m.get("timeline_pinned_user_ids"))
                    || nonEmptyList(m.get("clips_tab_pinned_user_ids"))
                    || nonEmptyList(m.get("pinned_for_users"));              // SELF_GQL
            out.add(new MediaItem(code, Instant.ofEpochSecond(takenAt), type, pinned));
        }
        return out;
    }

    /**
     * 프로필 원형(web_profile_info)에 최근 타임라인이 내장돼 있는지 — 빈 edges도 "내장 있음"
     * (게시물 0개 계정에 피드 폴백 요청을 낭비하지 않기 위해 존재 여부와 아이템 유무를 구분).
     */
    public static boolean hasEmbeddedTimeline(Map<String, Object> profilePayload) {
        return timelineEdges(profilePayload) != null;
    }

    /** 다음 페이지 커서. null이면 끝. */
    public static String nextCursor(Map<String, Object> payload, RawSource source) {
        return switch (source) {
            case HIKER_GQL_MEDIAS -> Boolean.TRUE.equals(payload.get("more_available"))
                    && payload.get("profile_grid_items_cursor") instanceof String s && !s.isBlank()
                    ? s : null;
            case HIKER_V2_CLIPS -> payload.get("next_page_id") instanceof String s && !s.isBlank()
                    ? s : null;
            default -> null;
        };
    }

    private static List<?> items(Map<String, Object> payload, RawSource source) {
        Object items = switch (source) {
            case HIKER_GQL_MEDIAS -> payload.get("items");
            case HIKER_V2_CLIPS -> payload.get("response") instanceof Map<?, ?> r
                    ? r.get("items") : null;
            case SELF_GQL -> timelineEdges(payload);
            default -> null;
        };
        return items instanceof List<?> l ? l : List.of();
    }

    /**
     * edge_owner_to_timeline_media.edges — 없으면 null(내장 아님).
     * 루트는 두 형태: SELF 직접 크롤은 data.user, Hiker /gql/user/web_profile_info는
     * data 래퍼를 벗긴 user 루트로 같은 원형을 준다.
     */
    private static List<?> timelineEdges(Map<String, Object> payload) {
        Object user = payload.get("data") instanceof Map<?, ?> d ? d.get("user") : payload.get("user");
        if (!(user instanceof Map<?, ?> u)) return null;
        Object cur = u.get("edge_owner_to_timeline_media");
        return cur instanceof Map<?, ?> m && m.get("edges") instanceof List<?> l ? l : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrapMedia(Map<?, ?> item) {
        if (item.get("media") instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (item.get("node") instanceof Map<?, ?> n) return (Map<String, Object>) n;  // SELF_GQL edges
        return (Map<String, Object>) item;
    }

    private static String firstString(Object... candidates) {
        for (Object c : candidates) {
            if (c instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    /** hiker flat 접두사 대응: key → 1l+key → 1f+key. */
    static Object get(Map<String, Object> m, String key) {
        if (m.containsKey(key)) return m.get(key);
        if (m.containsKey("1l" + key)) return m.get("1l" + key);
        return m.get("1f" + key);
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static boolean nonEmptyList(Object v) {
        return v instanceof List<?> l && !l.isEmpty();
    }

    private MediaItemExtractor() {}
}
