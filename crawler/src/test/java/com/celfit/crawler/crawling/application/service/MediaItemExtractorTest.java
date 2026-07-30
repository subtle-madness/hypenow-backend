package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.domain.RawSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MediaItemExtractorTest {

    @Test
    void gql_flat_페이지에서_1l접두사_시각과_고정여부를_추출한다() {
        Map<String, Object> payload = Map.of(
                "items", List.of(
                        Map.of("code", "PIN1", "1ltaken_at", 1745000000L, "product_type", "clips",
                                "timeline_pinned_user_ids", List.of("74969123775")),
                        Map.of("code", "NEW1", "1ltaken_at", 1783474981L, "product_type", "clips",
                                "timeline_pinned_user_ids", List.of()),
                        Map.of("code", "CAR1", "1ltaken_at", 1783474000L, "product_type", "carousel_container")),
                "more_available", true,
                "profile_grid_items_cursor", "CURSOR_X");

        List<MediaItemExtractor.MediaItem> items =
                MediaItemExtractor.extract(payload, RawSource.HIKER_GQL_MEDIAS);

        assertThat(items).hasSize(3);
        assertThat(items.get(0).pinned()).isTrue();
        assertThat(items.get(1)).isEqualTo(new MediaItemExtractor.MediaItem(
                "NEW1", Instant.ofEpochSecond(1783474981L), ContentType.REELS, false, ""));
        assertThat(items.get(2).type()).isEqualTo(ContentType.FEED);
        assertThat(MediaItemExtractor.nextCursor(payload, RawSource.HIKER_GQL_MEDIAS)).isEqualTo("CURSOR_X");
    }

    @Test
    void v2_clips_페이지는_response_items_media를_언랩한다() {
        Map<String, Object> payload = Map.of(
                "response", Map.of("items", List.of(
                        Map.of("media", Map.of("code", "CLIP1", "taken_at", 1783223195L,
                                "product_type", "clips")))),
                "next_page_id", "PAGE2");

        List<MediaItemExtractor.MediaItem> items =
                MediaItemExtractor.extract(payload, RawSource.HIKER_V2_CLIPS);

        assertThat(items).containsExactly(new MediaItemExtractor.MediaItem(
                "CLIP1", Instant.ofEpochSecond(1783223195L), ContentType.REELS, false, ""));
        assertThat(MediaItemExtractor.nextCursor(payload, RawSource.HIKER_V2_CLIPS)).isEqualTo("PAGE2");
    }

    @Test
    void 커서가_없거나_more_available_false면_null() {
        assertThat(MediaItemExtractor.nextCursor(
                Map.of("more_available", false, "profile_grid_items_cursor", "X"),
                RawSource.HIKER_GQL_MEDIAS)).isNull();
        assertThat(MediaItemExtractor.nextCursor(Map.of(), RawSource.HIKER_V2_CLIPS)).isNull();
    }

    @Test
    void code_없는_아이템은_건너뛴다() {
        Map<String, Object> payload = Map.of("items", List.of(Map.of("1ltaken_at", 1L)));
        assertThat(MediaItemExtractor.extract(payload, RawSource.HIKER_GQL_MEDIAS)).isEmpty();
    }

    // ---- SELF_GQL: web_profile_info 프로필 원형에 내장된 최근 12개 타임라인 ----

    static Map<String, Object> profileWithTimeline(List<Map<String, Object>> nodes) {
        return Map.of("data", Map.of("user", Map.of(
                "edge_owner_to_timeline_media", Map.of(
                        "count", 46,
                        "edges", nodes.stream().map(n -> (Object) Map.of("node", n)).toList()))));
    }

    @Test
    void self_gql_프로필_내장_타임라인에서_shortcode_시각_유형_고정여부를_추출한다() {
        Map<String, Object> payload = profileWithTimeline(List.of(
                Map.of("shortcode", "FEED1", "taken_at_timestamp", 1773630245L, "product_type", "",
                        "pinned_for_users", List.of(Map.of("id", "7231248475"))),
                Map.of("shortcode", "REEL1", "taken_at_timestamp", 1781092809L, "product_type", "clips",
                        "pinned_for_users", List.of()),
                Map.of("shortcode", "FEED2", "taken_at_timestamp", 1783852665L)));

        List<MediaItemExtractor.MediaItem> items =
                MediaItemExtractor.extract(payload, RawSource.SELF_GQL);

        assertThat(items).hasSize(3);
        assertThat(items.get(0)).isEqualTo(new MediaItemExtractor.MediaItem(
                "FEED1", Instant.ofEpochSecond(1773630245L), ContentType.FEED, true, ""));
        assertThat(items.get(1)).isEqualTo(new MediaItemExtractor.MediaItem(
                "REEL1", Instant.ofEpochSecond(1781092809L), ContentType.REELS, false, ""));
        assertThat(items.get(2).pinned()).isFalse();
    }

    @Test
    void self_gql_타임라인_내장_여부를_판별한다() {
        // 내장 있음(빈 edges 포함 — 게시물 0개 계정도 "내장 있음"이라 피드 폴백 호출이 없어야 한다)
        assertThat(MediaItemExtractor.hasEmbeddedTimeline(profileWithTimeline(List.of()))).isTrue();
        // 내장 없음 — HIKER_MOBILE by/username 형태 또는 프로필 미확보
        assertThat(MediaItemExtractor.hasEmbeddedTimeline(
                Map.of("user", Map.of("username", "alice", "pk", "1")))).isFalse();
    }

    @Test
    void self_gql_커서는_항상_null이다() {
        assertThat(MediaItemExtractor.nextCursor(profileWithTimeline(List.of()), RawSource.SELF_GQL)).isNull();
    }

    // ---- HIKER_WEB_GQL: 같은 web_profile_info지만 루트가 data.user가 아니라 user ----

    /** Hiker /gql/user/web_profile_info는 IG 원형에서 data 래퍼를 벗긴 {"user": {...}} 형태다. */
    static Map<String, Object> hikerProfileWithTimeline(List<Map<String, Object>> nodes) {
        return Map.of("user", Map.of(
                "username", "alice", "pk", "1",
                "edge_owner_to_timeline_media", Map.of(
                        "count", 46,
                        "edges", nodes.stream().map(n -> (Object) Map.of("node", n)).toList())));
    }

    // ---- HIKER_V1_MEDIAS: /v1/user/medias/chunk — 모바일 계열, [medias, cursor]를 Map으로 감싼 형태 ----

    @Test
    void v1_medias_chunk_페이지에서_ISO_시각과_유형을_추출하고_커서를_읽는다() {
        Map<String, Object> payload = Map.of(
                "medias", List.of(
                        Map.of("code", "C_FEED", "taken_at", "2026-07-18T01:00:00", "product_type", ""),
                        Map.of("code", "C_REEL", "taken_at", "2026-07-18T02:00:00Z", "product_type", "clips"),
                        Map.of("code", "C_EPOCH", "taken_at", 1773630245L)),
                "next_end_cursor", "CUR9");

        List<MediaItemExtractor.MediaItem> items =
                MediaItemExtractor.extract(payload, RawSource.HIKER_V1_MEDIAS);

        assertThat(items).hasSize(3);
        assertThat(items.get(0)).isEqualTo(new MediaItemExtractor.MediaItem(
                "C_FEED", Instant.parse("2026-07-18T01:00:00Z"), ContentType.FEED, false, ""));
        assertThat(items.get(1).type()).isEqualTo(ContentType.REELS);
        assertThat(items.get(2).takenAt()).isEqualTo(Instant.ofEpochSecond(1773630245L));
        assertThat(MediaItemExtractor.nextCursor(payload, RawSource.HIKER_V1_MEDIAS)).isEqualTo("CUR9");
    }

    @Test
    void hiker_webgql_프로필의_user_루트_내장_타임라인도_판별하고_추출한다() {
        Map<String, Object> payload = hikerProfileWithTimeline(List.of(
                Map.of("shortcode", "HFEED", "taken_at_timestamp", 1773630245L, "product_type", ""),
                Map.of("shortcode", "HREEL", "taken_at_timestamp", 1781092809L, "product_type", "clips")));

        assertThat(MediaItemExtractor.hasEmbeddedTimeline(payload)).isTrue();
        List<MediaItemExtractor.MediaItem> items =
                MediaItemExtractor.extract(payload, RawSource.SELF_GQL);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).shortCode()).isEqualTo("HFEED");
        assertThat(items.get(1).type()).isEqualTo(ContentType.REELS);
    }

    // ---- 캡션 원문 추출 — 세 소스가 형태가 다르다(중첩 객체 / edges 배열 / 평문) ----

    @Test
    void v2_clips는_caption_text_중첩객체에서_캡션을_뽑는다() {
        Map<String, Object> payload = Map.of(
                "response", Map.of("items", List.of(
                        Map.of("media", Map.of("code", "CLIP1", "taken_at", 1783223195L,
                                "product_type", "clips",
                                "caption", Map.of("text", "#광고 여름 메리제인 🤍"))))));

        List<MediaItemExtractor.MediaItem> items =
                MediaItemExtractor.extract(payload, RawSource.HIKER_V2_CLIPS);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).caption()).isEqualTo("#광고 여름 메리제인 🤍");
    }

    @Test
    void self_gql은_edge_media_to_caption_첫_노드에서_캡션을_뽑는다() {
        Map<String, Object> payload = profileWithTimeline(List.of(
                Map.of("shortcode", "FEED1", "taken_at_timestamp", 1773630245L,
                        "edge_media_to_caption", Map.of("edges", List.of(
                                Map.of("node", Map.of("text", "매일 쓰는 메이크업 도구")))))));

        List<MediaItemExtractor.MediaItem> items =
                MediaItemExtractor.extract(payload, RawSource.SELF_GQL);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).caption()).isEqualTo("매일 쓰는 메이크업 도구");
    }

    @Test
    void v1_medias는_caption_text_평문필드에서_캡션을_뽑는다() {
        Map<String, Object> payload = Map.of("medias", List.of(
                Map.of("code", "C_FEED", "taken_at", 1773630245L,
                        "caption_text", "고마어 잘 쓸게~~ 🤍")));

        List<MediaItemExtractor.MediaItem> items =
                MediaItemExtractor.extract(payload, RawSource.HIKER_V1_MEDIAS);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).caption()).isEqualTo("고마어 잘 쓸게~~ 🤍");
    }

    /** 캡션 없는 게시물은 null이 아니라 빈 문자열 — "미확인"과 "캡션 없음"을 DB에서 구분하기 위함. */
    @Test
    void 캡션이_없으면_빈_문자열이다() {
        Map<String, Object> payload = Map.of("medias", List.of(
                Map.of("code", "NOCAP", "taken_at", 1773630245L)));

        assertThat(MediaItemExtractor.extract(payload, RawSource.HIKER_V1_MEDIAS).get(0).caption())
                .isEqualTo("");
    }

    /** hasEmbeddedTimeline의 빈 edges 검증과 짝 — edges가 비어도 IndexOutOfBounds 없이 빈 문자열. */
    @Test
    void self_gql은_edges가_비어있으면_빈_문자열이다() {
        Map<String, Object> payload = profileWithTimeline(List.of(
                Map.of("shortcode", "FEED1", "taken_at_timestamp", 1773630245L,
                        "edge_media_to_caption", Map.of("edges", List.of()))));

        assertThat(MediaItemExtractor.extract(payload, RawSource.SELF_GQL).get(0).caption())
                .isEqualTo("");
    }

    // ---- captions: 저장된 릴스 페이지에서 캡션 추출 ----

    @Test
    void 릴스_페이지에서_캡션을_뽑는다() {
        Map<String, Object> payload = Map.of("response", Map.of("items", List.of(
                Map.of("media", Map.of("code", "AAA", "taken_at", 1_700_000_000L,
                        "caption", Map.of("text", "오늘의 스킨케어 루틴"))),
                Map.of("media", Map.of("code", "BBB", "taken_at", 1_700_000_100L,
                        "caption", Map.of("text", "신상 쿠션 발색샷"))))));

        assertThat(MediaItemExtractor.captions(payload, RawSource.HIKER_V2_CLIPS))
                .containsExactly("오늘의 스킨케어 루틴", "신상 쿠션 발색샷");
    }

    @Test
    void 캡션이_없거나_빈_아이템은_건너뛴다() {
        Map<String, Object> payload = Map.of("response", Map.of("items", List.of(
                Map.of("media", Map.of("code", "AAA", "caption", Map.of("text", "  "))),
                Map.of("media", Map.of("code", "BBB")),
                Map.of("media", Map.of("code", "CCC", "caption", Map.of("text", "유효 캡션"))))));

        assertThat(MediaItemExtractor.captions(payload, RawSource.HIKER_V2_CLIPS))
                .containsExactly("유효 캡션");
    }

    @Test
    void 검증되지_않은_소스는_빈_리스트다() {
        Map<String, Object> payload = Map.of("medias", List.of(
                Map.of("code", "AAA", "caption", Map.of("text", "피드 캡션"))));

        assertThat(MediaItemExtractor.captions(payload, RawSource.HIKER_V1_MEDIAS)).isEmpty();
    }
}
