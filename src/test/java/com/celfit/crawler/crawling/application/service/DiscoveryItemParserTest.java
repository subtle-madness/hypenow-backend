package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.content.domain.ContentType;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscoveryItemParserTest {

    @Test
    void 정상_아이템_파싱_clips는_REELS() {
        Map<String, Object> item = Map.of(
                "shortCode", "abc123",
                "productType", "clips",
                "timestamp", "2026-07-01T12:00:00.000Z",
                "ownerUsername", "kim");
        var parsed = DiscoveryItemParser.parse(item).orElseThrow();
        assertThat(parsed.shortCode()).isEqualTo("abc123");
        assertThat(parsed.type()).isEqualTo(ContentType.REELS);
        assertThat(parsed.uploadedAt()).isEqualTo(Instant.parse("2026-07-01T12:00:00Z"));
        assertThat(parsed.ownerUsername()).isEqualTo("kim");
    }

    @Test
    void productType_clips가_아니면_FEED() {
        Map<String, Object> item = Map.of(
                "shortCode", "abc", "timestamp", "2026-07-01T12:00:00.000Z", "ownerUsername", "kim");
        assertThat(DiscoveryItemParser.parse(item).orElseThrow().type()).isEqualTo(ContentType.FEED);
    }

    @Test
    void 필수_필드가_없거나_timestamp가_깨지면_empty() {
        assertThat(DiscoveryItemParser.parse(Map.of("shortCode", "a", "ownerUsername", "k"))).isEmpty();
        assertThat(DiscoveryItemParser.parse(Map.of("timestamp", "2026-07-01T12:00:00.000Z", "ownerUsername", "k"))).isEmpty();
        assertThat(DiscoveryItemParser.parse(Map.of("shortCode", "a", "timestamp", "언제더라", "ownerUsername", "k"))).isEmpty();
    }
}
