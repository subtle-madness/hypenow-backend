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
                "NEW1", Instant.ofEpochSecond(1783474981L), ContentType.REELS, false));
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
                "CLIP1", Instant.ofEpochSecond(1783223195L), ContentType.REELS, false));
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
                "FEED1", Instant.ofEpochSecond(1773630245L), ContentType.FEED, true));
        assertThat(items.get(1)).isEqualTo(new MediaItemExtractor.MediaItem(
                "REEL1", Instant.ofEpochSecond(1781092809L), ContentType.REELS, false));
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
}
