package com.celfit.crawler.job;

import com.celfit.crawler.domain.ContentType;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/** 발굴 액터 아이템에서 제어 인덱스 필드만 추출. raw payload는 손대지 않는다. */
public final class DiscoveryItemParser {

    public record DiscoveredItem(String shortCode, ContentType type, Instant uploadedAt,
                                 String ownerUsername, Map<String, Object> payload) {}

    public static Optional<DiscoveredItem> parse(Map<String, Object> item) {
        String shortCode = str(item, "shortCode");
        String timestamp = str(item, "timestamp");
        String owner = str(item, "ownerUsername");
        if (shortCode == null || timestamp == null || owner == null) return Optional.empty();
        Instant uploadedAt;
        try {
            uploadedAt = Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        ContentType type = "clips".equals(str(item, "productType")) ? ContentType.REELS : ContentType.FEED;
        return Optional.of(new DiscoveredItem(shortCode, type, uploadedAt, owner, item));
    }

    private static String str(Map<String, Object> map, String key) {
        return map.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    private DiscoveryItemParser() {}
}
