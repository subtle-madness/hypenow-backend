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
            String code = m.get("code") instanceof String s && !s.isBlank() ? s : null;
            Long takenAt = asLong(get(m, "taken_at"));
            if (code == null || takenAt == null) continue;
            ContentType type = "clips".equals(m.get("product_type"))
                    ? ContentType.REELS : ContentType.FEED;
            boolean pinned = nonEmptyList(m.get("timeline_pinned_user_ids"))
                    || nonEmptyList(m.get("clips_tab_pinned_user_ids"));
            out.add(new MediaItem(code, Instant.ofEpochSecond(takenAt), type, pinned));
        }
        return out;
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
            default -> null;
        };
        return items instanceof List<?> l ? l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrapMedia(Map<?, ?> item) {
        return item.get("media") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : (Map<String, Object>) item;
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
