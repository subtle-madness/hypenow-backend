package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.domain.RawSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 프로필 응답 원형에서 제어 필드(followers·userId·username) 추출. 소스별 경로. */
public final class ProfileExtractor {

    /**
     * 혼합 배치(SELF 베이스 + 400 Hiker 폴백)의 아이템별 소스 감지. SELF_GQL 원형은 루트에
     * "data", HIKER_MOBILE 원형은 "user" 래퍼 또는 flat "pk"가 있다. SELF_GQL 기본이 아닌
     * 배치는 감지하지 않는다 — DATALIKERS 등 flat 원형이 HIKER_MOBILE로 오기록되는 것을 막는다.
     */
    public static RawSource detect(Map<String, Object> payload, RawSource defaultSource) {
        if (defaultSource != RawSource.SELF_GQL) return defaultSource;
        if (payload.get("data") instanceof Map) return RawSource.SELF_GQL;
        if (payload.get("user") instanceof Map || payload.containsKey("pk")) return RawSource.HIKER_MOBILE;
        return defaultSource;
    }

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

    /**
     * 최근 게시물 캡션(공백·누락 제외, 응답 순서 유지). 게시물이 payload에 없는 소스
     * (HIKER_MOBILE·DATALIKERS)는 빈 리스트. LEGACY_ENVELOPE는 구형 latestPosts[].caption과
     * 신형 _rawProfile.data.user(GQL 타임라인 중첩) 두 변형을 모두 처리한다.
     */
    public static List<String> recentCaptions(Map<String, Object> payload, RawSource source) {
        return switch (source) {
            case SELF_GQL -> gqlTimelineCaptions(dig(payload, "data", "user"));
            case LEGACY_ENVELOPE, APIFY_ACTOR -> {
                if (payload.get("latestPosts") instanceof List<?> posts) {
                    List<String> out = new ArrayList<>();
                    for (Object post : posts) {
                        if (post instanceof Map<?, ?> m && asText(m.get("caption")) instanceof String c) {
                            out.add(c);
                        }
                    }
                    yield out;
                }
                yield gqlTimelineCaptions(dig(payload, "_rawProfile", "data", "user"));
            }
            default -> List.of();
        };
    }

    /** GQL user 객체의 edge_owner_to_timeline_media.edges[].node.edge_media_to_caption.edges[0].node.text */
    private static List<String> gqlTimelineCaptions(Object user) {
        List<String> out = new ArrayList<>();
        if (!(user instanceof Map<?, ?> u)) return out;
        if (!(dig(castMap(u), "edge_owner_to_timeline_media", "edges") instanceof List<?> edges)) return out;
        for (Object edge : edges) {
            if (!(edge instanceof Map<?, ?> e)) continue;
            if (!(dig(castMap(e), "node", "edge_media_to_caption", "edges") instanceof List<?> capEdges)) continue;
            for (Object capEdge : capEdges) {
                if (capEdge instanceof Map<?, ?> ce
                        && asText(dig(castMap(ce), "node", "text")) instanceof String c) {
                    out.add(c);
                    break;  // 게시물 하나에 캡션 하나 — 첫 유효 캡션만
                }
            }
        }
        return out;
    }

    /** 첫 non-blank 값(공백·빈 문자열은 '없음'으로 간주 — asText 기준과 일치) 반환, 없으면 null. */
    private static Object first(Object... vals) {
        for (Object v : vals) {
            if (v instanceof String s) {
                if (!s.isBlank()) return v;
            } else if (v != null) {
                return v;
            }
        }
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
