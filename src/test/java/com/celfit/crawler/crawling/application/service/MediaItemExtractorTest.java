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
}
