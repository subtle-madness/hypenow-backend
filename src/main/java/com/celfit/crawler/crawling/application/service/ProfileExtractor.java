package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.domain.RawSource;
import java.util.Map;

/** 프로필 응답 원형에서 제어 필드(followers·userId·username) 추출. 소스별 경로. */
public final class ProfileExtractor {

    public static Long followers(Map<String, Object> payload, RawSource source) {
        return switch (source) {
            case SELF_GQL -> asLong(dig(payload, "data", "user", "edge_followed_by", "count"));
            // DataLikers /v1/user/by/username은 flat 유저 객체(follower_count·pk 최상위) — HIKER_MOBILE과
            // 동일 구조라(user() 헬퍼가 "user" 래퍼 유무 모두 처리) 같은 경로를 쓴다.
            case HIKER_MOBILE, DATALIKERS -> asLong(dig(user(payload), "follower_count"));
            default -> asLong(payload.get("followersCount"));  // LEGACY_ENVELOPE·APIFY_ACTOR
        };
    }

    public static String userId(Map<String, Object> payload, RawSource source) {
        Object v = switch (source) {
            case SELF_GQL -> dig(payload, "data", "user", "id");
            case HIKER_MOBILE, DATALIKERS -> {
                Object pk = dig(user(payload), "pk");
                yield pk != null ? pk : dig(user(payload), "id");
            }
            default -> payload.get("userId");
        };
        return v == null ? null : String.valueOf(v);
    }

    public static String username(Map<String, Object> payload, RawSource source) {
        Object v = switch (source) {
            case SELF_GQL -> dig(payload, "data", "user", "username");
            case HIKER_MOBILE, DATALIKERS -> dig(user(payload), "username");
            default -> payload.get("username");
        };
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    public static String fullName(Map<String, Object> payload, RawSource source) {
        Object v = switch (source) {
            case SELF_GQL -> dig(payload, "data", "user", "full_name");
            case HIKER_MOBILE, DATALIKERS -> dig(user(payload), "full_name");
            default -> payload.get("fullName");
        };
        return asText(v);
    }

    public static String category(Map<String, Object> payload, RawSource source) {
        Object v = switch (source) {
            case SELF_GQL -> first(dig(payload, "data", "user", "category_name"),
                    dig(payload, "data", "user", "business_category_name"));
            case HIKER_MOBILE, DATALIKERS -> first(dig(user(payload), "category"),
                    dig(user(payload), "category_name"), dig(user(payload), "business_category_name"));
            default -> payload.get("businessCategoryName");
        };
        return asText(v);
    }

    public static String biography(Map<String, Object> payload, RawSource source) {
        Object v = switch (source) {
            case SELF_GQL -> dig(payload, "data", "user", "biography");
            case HIKER_MOBILE, DATALIKERS -> dig(user(payload), "biography");
            default -> payload.get("biography");
        };
        return asText(v);
    }

    private static Object first(Object... vals) {
        for (Object v : vals) if (v != null) return v;
        return null;
    }

    private static String asText(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static Map<String, Object> user(Map<String, Object> payload) {
        return payload.get("user") instanceof Map<?, ?> u
                ? castMap(u) : payload;
    }

    private static Object dig(Map<String, Object> m, String... path) {
        Object cur = m;
        for (String p : path) {
            if (!(cur instanceof Map<?, ?> mm)) return null;
            cur = mm.get(p);
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
        return null;
    }

    private ProfileExtractor() {}
}
