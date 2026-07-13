package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.domain.RawSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfileExtractorTest {

    @Test
    void self_gql_nested_path_extracts_followers_id_username() {
        Map<String, Object> payload = Map.of(
                "data", Map.of(
                        "user", Map.of(
                                "edge_followed_by", Map.of("count", 5432L),
                                "id", "12345",
                                "username", "testuser")));

        assertThat(ProfileExtractor.followers(payload, RawSource.SELF_GQL)).isEqualTo(5432L);
        assertThat(ProfileExtractor.userId(payload, RawSource.SELF_GQL)).isEqualTo("12345");
        assertThat(ProfileExtractor.username(payload, RawSource.SELF_GQL)).isEqualTo("testuser");
    }

    @Test
    void hiker_mobile_user_object_extracts_fields() {
        Map<String, Object> payload = Map.of(
                "user", Map.of(
                        "follower_count", 9876L,
                        "pk", "67890",
                        "username", "hikeruser"));

        assertThat(ProfileExtractor.followers(payload, RawSource.HIKER_MOBILE)).isEqualTo(9876L);
        assertThat(ProfileExtractor.userId(payload, RawSource.HIKER_MOBILE)).isEqualTo("67890");
        assertThat(ProfileExtractor.username(payload, RawSource.HIKER_MOBILE)).isEqualTo("hikeruser");
    }

    @Test
    void hiker_mobile_pk_missing_fallback_to_id() {
        Map<String, Object> payload = Map.of(
                "user", Map.of(
                        "id", "99999",
                        "username", "fallbackuser"));

        assertThat(ProfileExtractor.userId(payload, RawSource.HIKER_MOBILE)).isEqualTo("99999");
    }

    @Test
    void legacy_envelope_flat_keys_extracts_fields() {
        Map<String, Object> payload = Map.of(
                "followersCount", 1000L,
                "userId", "legacy123",
                "username", "legacyuser");

        assertThat(ProfileExtractor.followers(payload, RawSource.LEGACY_ENVELOPE)).isEqualTo(1000L);
        assertThat(ProfileExtractor.userId(payload, RawSource.LEGACY_ENVELOPE)).isEqualTo("legacy123");
        assertThat(ProfileExtractor.username(payload, RawSource.LEGACY_ENVELOPE)).isEqualTo("legacyuser");
    }

    @Test
    void missing_fields_return_null() {
        Map<String, Object> emptyPayload = Map.of();

        assertThat(ProfileExtractor.followers(emptyPayload, RawSource.SELF_GQL)).isNull();
        assertThat(ProfileExtractor.userId(emptyPayload, RawSource.SELF_GQL)).isNull();
        assertThat(ProfileExtractor.username(emptyPayload, RawSource.SELF_GQL)).isNull();

        assertThat(ProfileExtractor.followers(emptyPayload, RawSource.HIKER_MOBILE)).isNull();
        assertThat(ProfileExtractor.userId(emptyPayload, RawSource.HIKER_MOBILE)).isNull();
        assertThat(ProfileExtractor.username(emptyPayload, RawSource.HIKER_MOBILE)).isNull();

        assertThat(ProfileExtractor.followers(emptyPayload, RawSource.LEGACY_ENVELOPE)).isNull();
        assertThat(ProfileExtractor.userId(emptyPayload, RawSource.LEGACY_ENVELOPE)).isNull();
        assertThat(ProfileExtractor.username(emptyPayload, RawSource.LEGACY_ENVELOPE)).isNull();
    }

    @Test
    void blank_username_returns_null() {
        Map<String, Object> payload = Map.of(
                "data", Map.of(
                        "user", Map.of(
                                "username", "")));

        assertThat(ProfileExtractor.username(payload, RawSource.SELF_GQL)).isNull();
    }
}
