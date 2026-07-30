package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.domain.RawSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 저장된 열거 페이지 원형에서 제어 필드와 캡션 원문을 추출. 원형은 이미 raw_media_page에 있으므로
 * 여기서의 결손·실패는 데이터 유실이 아니다(해당 아이템만 건너뜀).
 * Hiker gql flat은 숫자 필드에 1l/1f 접두사를 붙인다 — get()이 3형을 순서대로 조회.
 */
public final class MediaItemExtractor {

    /**
     * @param caption 캡션 원문 — 3-상태다. {@code null}=이 payload 형태에서 캡션을 읽는 방법을
     *                모름(미확인), {@code ""}=형태를 인식했고 캡션이 없음을 확인함, 그 외=캡션
     *                원문. null과 ""를 구분하는 이유: 구분하지 않으면 같은 content가 다른(더
     *                최신) 소스 페이지에 다시 등장했을 때 "미확인"의 빈 문자열이 이미 확보한
     *                실제 캡션을 조용히 덮어쓴다(ContentCaptionUpserter는 null 아이템을 아예
     *                배치에서 제외한다).
     */
    public record MediaItem(String shortCode, Instant takenAt, ContentType type, boolean pinned,
                            String caption) {}

    public static List<MediaItem> extract(Map<String, Object> payload, RawSource source) {
        List<MediaItem> out = new ArrayList<>();
        for (Object o : items(payload, source)) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> m = unwrapMedia(raw);
            String code = firstString(m.get("code"), m.get("shortcode"));   // SELF_GQL은 shortcode
            Instant takenAt = takenAtOf(get(m, "taken_at"));
            if (takenAt == null) takenAt = takenAtOf(m.get("taken_at_timestamp")); // SELF_GQL
            if (code == null || takenAt == null) continue;
            ContentType type = "clips".equals(m.get("product_type"))
                    ? ContentType.REELS : ContentType.FEED;
            boolean pinned = nonEmptyList(m.get("timeline_pinned_user_ids"))
                    || nonEmptyList(m.get("clips_tab_pinned_user_ids"))
                    || nonEmptyList(m.get("pinned_for_users"));              // SELF_GQL
            out.add(new MediaItem(code, takenAt, type, pinned, captionOf(m)));
        }
        return out;
    }

    /**
     * 저장된 열거 페이지에서 게시물 캡션 추출 — 뷰티 재판정의 실측 근거다.
     * HIKER_V2_CLIPS만 지원한다: response.items[].media.caption.text
     * (analytics v_base_reel_item이 캡션을 뽑는 경로와 동일).
     * HIKER_V1_MEDIAS는 payload에 캡션이 있는지 검증한 뷰도 픽스처도 없어 제외한다 —
     * 근거 없는 경로로 빈 문자열을 판정 재료에 섞느니 아예 넣지 않는다.
     */
    public static List<String> captions(Map<String, Object> payload, RawSource source) {
        if (source != RawSource.HIKER_V2_CLIPS) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : items(payload, source)) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> m = unwrapMedia(raw);
            if (m.get("caption") instanceof Map<?, ?> cap
                    && cap.get("text") instanceof String t && !t.isBlank()) {
                out.add(t);
            }
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
            case HIKER_V1_MEDIAS -> payload.get("next_end_cursor") instanceof String s && !s.isBlank()
                    ? s : null;
            default -> null;
        };
    }

    private static List<?> items(Map<String, Object> payload, RawSource source) {
        Object items = switch (source) {
            case HIKER_GQL_MEDIAS -> payload.get("items");
            case HIKER_V2_CLIPS -> payload.get("response") instanceof Map<?, ?> r
                    ? r.get("items") : null;
            case HIKER_V1_MEDIAS -> payload.get("medias");
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

    /**
     * 캡션 원문 — unwrapMedia()가 media/node/item을 이미 벗겨냈으므로 정규화된 맵 하나에서
     * 세 형태를 순서대로 시도한다. HIKER_V2_CLIPS는 caption.text(중첩 객체),
     * HIKER_V1_MEDIAS는 caption_text(평문), SELF_GQL은 edge_media_to_caption.edges[0].node.text.
     *
     * <p>반환은 3-상태다 — {@code null}=이 형태의 캡션 키 자체를 못 찾음(미확인),
     * {@code ""}=키는 찾았고 값이 비어 있음(확인된 무캡션), 그 외=캡션 원문. 이 구분이 없으면
     * (옛 구현처럼 못 찾을 때도 "") 같은 content가 여러 소스 페이지에 등장할 때(타임라인은
     * 릴스도 담고 V1_MEDIAS는 피드·릴스를 함께 담는다) 더 최신 페이지에서 이 형태를 못 읽으면
     * ""가 실제로 확보해 둔 캡션을 조용히 덮어쓴다.
     *
     * <p>HIKER_V2_CLIPS는 키 존재 여부(containsKey)로 판정한다 — 운영 실측(2026-07-30,
     * 표본 2,905건)에서 캡션 없는 게시물은 키 부재가 아니라 {@code "caption": null}로
     * 표현됨을 확인했다(24건, 0.83%). {@code Map.get()}은 "키 없음"과 "값이 JSON null"을
     * 구분하지 못해 이 값을 "미확인"으로 오판하면 해당 0.83%에 대해 행이 생성되지 않는다
     * (커버리지 누락, "행 부재=미확인" 계약 위반). 그래서 키가 있으면 값이 null이거나
     * 캡션 객체가 아니어도 "확인된 무캡션"(빈 문자열)으로 다룬다. caption_text
     * (V1_MEDIAS)도 이론상 같은 함정이 가능하지만 실측(표본 2,393건)에서 명시적 null이
     * 0건이라 이 세션에서는 건드리지 않는다 — 향후 재현되면 같은 패턴을 적용할 것.
     */
    private static String captionOf(Map<String, Object> m) {
        if (m.containsKey("caption")) {
            Object c = m.get("caption");
            return c instanceof Map<?, ?> cm && cm.get("text") instanceof String s ? s : "";
        }
        if (m.get("caption_text") instanceof String s) {
            return s;
        }
        if (m.get("edge_media_to_caption") instanceof Map<?, ?> e) {
            if (!(e.get("edges") instanceof List<?> edges)) return null;
            if (edges.isEmpty()) return "";
            return edges.get(0) instanceof Map<?, ?> first
                    && first.get("node") instanceof Map<?, ?> node
                    && node.get("text") instanceof String s ? s : null;
        }
        return null;
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

    /**
     * 게시 시각 — epoch 초(Number, SELF·gql flat)와 ISO 문자열(Hiker /v1 serialized Media) 모두 허용.
     * 오프셋 없는 ISO는 UTC로 해석한다.
     */
    private static Instant takenAtOf(Object v) {
        if (v instanceof Number n) return Instant.ofEpochSecond(n.longValue());
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Instant.parse(s);
            } catch (java.time.format.DateTimeParseException e) {
                try {
                    return java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC);
                } catch (java.time.format.DateTimeParseException e2) {
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean nonEmptyList(Object v) {
        return v instanceof List<?> l && !l.isEmpty();
    }

    private MediaItemExtractor() {}
}
