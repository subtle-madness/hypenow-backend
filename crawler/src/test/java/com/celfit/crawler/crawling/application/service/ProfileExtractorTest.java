package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.domain.RawSource;

import java.util.LinkedHashMap;
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
    void datalikers_flat_user_object_extracts_fields() {
        // DataLikers /v1/user/by/username — "user" 래퍼 없이 flat. user() 헬퍼가 root를 그대로 쓴다.
        Map<String, Object> payload = Map.of(
                "follower_count", 256559L,
                "pk", "74756186520",
                "username", "tem.duck");

        assertThat(ProfileExtractor.followers(payload, RawSource.DATALIKERS)).isEqualTo(256559L);
        assertThat(ProfileExtractor.userId(payload, RawSource.DATALIKERS)).isEqualTo("74756186520");
        assertThat(ProfileExtractor.username(payload, RawSource.DATALIKERS)).isEqualTo("tem.duck");
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

    @Test
    void 뷰티_판정_재료를_소스별_경로에서_추출한다() {
        // LEGACY_ENVELOPE — 최상위 평탄 구조
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("fullName", "에텔랑화장품");
        legacy.put("businessCategoryName", "Beauty, cosmetic & personal care");
        legacy.put("biography", "화장품 브랜드");
        assertThat(ProfileExtractor.fullName(legacy, RawSource.LEGACY_ENVELOPE)).isEqualTo("에텔랑화장품");
        assertThat(ProfileExtractor.category(legacy, RawSource.LEGACY_ENVELOPE))
                .isEqualTo("Beauty, cosmetic & personal care");
        assertThat(ProfileExtractor.biography(legacy, RawSource.LEGACY_ENVELOPE)).isEqualTo("화장품 브랜드");

        // HIKER_MOBILE — user 래퍼 + category, 폴백은 category_name
        Map<String, Object> hikerUser = new LinkedHashMap<>();
        hikerUser.put("full_name", "뷰티 크리에이터");
        hikerUser.put("category", "Digital creator");
        hikerUser.put("biography", "메이크업");
        Map<String, Object> hiker = Map.of("user", hikerUser);
        assertThat(ProfileExtractor.fullName(hiker, RawSource.HIKER_MOBILE)).isEqualTo("뷰티 크리에이터");
        assertThat(ProfileExtractor.category(hiker, RawSource.HIKER_MOBILE)).isEqualTo("Digital creator");
        assertThat(ProfileExtractor.biography(hiker, RawSource.HIKER_MOBILE)).isEqualTo("메이크업");

        // DATALIKERS — 평탄 유저 객체(user 래퍼 없음), category 없고 category_name만
        Map<String, Object> dl = new LinkedHashMap<>();
        dl.put("full_name", "네일샵");
        dl.put("category_name", "Nail salon");
        dl.put("biography", "네일 아트");
        assertThat(ProfileExtractor.fullName(dl, RawSource.DATALIKERS)).isEqualTo("네일샵");
        assertThat(ProfileExtractor.category(dl, RawSource.DATALIKERS)).isEqualTo("Nail salon");
        assertThat(ProfileExtractor.biography(dl, RawSource.DATALIKERS)).isEqualTo("네일 아트");

        // SELF_GQL — data.user 중첩
        Map<String, Object> gqlUser = new LinkedHashMap<>();
        gqlUser.put("full_name", "스킨케어");
        gqlUser.put("category_name", "Health/beauty");
        gqlUser.put("biography", "피부 관리");
        Map<String, Object> gql = Map.of("data", Map.of("user", gqlUser));
        assertThat(ProfileExtractor.fullName(gql, RawSource.SELF_GQL)).isEqualTo("스킨케어");
        assertThat(ProfileExtractor.category(gql, RawSource.SELF_GQL)).isEqualTo("Health/beauty");
        assertThat(ProfileExtractor.biography(gql, RawSource.SELF_GQL)).isEqualTo("피부 관리");
    }

    @Test
    void category가_빈문자열이면_category_name_폴백으로_넘어간다() {
        // HIKER_MOBILE — category가 빈 문자열("")이면 first()가 값 있는 것으로 오판해 category_name
        // 폴백을 가리고 null로 붕괴하던 버그. 빈 문자열도 '없음' 취급해야 폴백이 살아난다.
        Map<String, Object> hikerUser = new LinkedHashMap<>();
        hikerUser.put("category", "");
        hikerUser.put("category_name", "Beauty salon");
        Map<String, Object> hiker = Map.of("user", hikerUser);

        assertThat(ProfileExtractor.category(hiker, RawSource.HIKER_MOBILE)).isEqualTo("Beauty salon");
    }

    @Test
    void 최근_게시물_캡션을_소스별_경로에서_추출한다() {
        // LEGACY_ENVELOPE — latestPosts[].caption. 공백·누락 캡션은 건너뛰고 순서는 유지한다.
        Map<String, Object> legacy = Map.of("latestPosts", java.util.List.of(
                Map.of("caption", "첫 캡션", "shortCode", "A"),
                Map.of("shortCode", "B"),
                Map.of("caption", "  ", "shortCode", "C"),
                Map.of("caption", "둘째 캡션", "shortCode", "D")));
        assertThat(ProfileExtractor.recentCaptions(legacy, RawSource.LEGACY_ENVELOPE))
                .containsExactly("첫 캡션", "둘째 캡션");

        // SELF_GQL — data.user.edge_owner_to_timeline_media.edges[].node.edge_media_to_caption.edges[0].node.text
        Map<String, Object> gql = Map.of("data", Map.of("user", Map.of(
                "edge_owner_to_timeline_media", Map.of("edges", java.util.List.of(
                        Map.of("node", Map.of("edge_media_to_caption", Map.of("edges", java.util.List.of(
                                Map.of("node", Map.of("text", "GQL 캡션")))))),
                        Map.of("node", Map.of("edge_media_to_caption", Map.of("edges", java.util.List.of()))))))));
        assertThat(ProfileExtractor.recentCaptions(gql, RawSource.SELF_GQL))
                .containsExactly("GQL 캡션");

        // LEGACY_ENVELOPE 신형 — latestPosts 없이 _rawProfile.data.user에 GQL 타임라인이 중첩된 변형
        Map<String, Object> legacyRaw = Map.of("_rawProfile", gql);
        assertThat(ProfileExtractor.recentCaptions(legacyRaw, RawSource.LEGACY_ENVELOPE))
                .containsExactly("GQL 캡션");

        // HIKER_MOBILE·DATALIKERS — 게시물 없음, 빈 payload도 빈 리스트
        assertThat(ProfileExtractor.recentCaptions(Map.of("user", Map.of()), RawSource.HIKER_MOBILE)).isEmpty();
        assertThat(ProfileExtractor.recentCaptions(Map.of(), RawSource.DATALIKERS)).isEmpty();
        assertThat(ProfileExtractor.recentCaptions(Map.of(), RawSource.SELF_GQL)).isEmpty();
        assertThat(ProfileExtractor.recentCaptions(Map.of(), RawSource.LEGACY_ENVELOPE)).isEmpty();
    }

    @Test
    void detect는_data_루트를_SELF_GQL로_감지한다() {
        Map<String, Object> payload = Map.of("data", Map.of("user", Map.of("username", "a")));
        assertThat(ProfileExtractor.detect(payload, RawSource.SELF_GQL)).isEqualTo(RawSource.SELF_GQL);
    }

    @Test
    void detect는_user_래퍼_또는_pk_최상위를_HIKER_MOBILE로_감지한다() {
        Map<String, Object> wrapped = Map.of("user", Map.of("username", "a", "pk", "1"));
        Map<String, Object> flat = Map.of("username", "a", "pk", "1");
        assertThat(ProfileExtractor.detect(wrapped, RawSource.SELF_GQL)).isEqualTo(RawSource.HIKER_MOBILE);
        assertThat(ProfileExtractor.detect(flat, RawSource.SELF_GQL)).isEqualTo(RawSource.HIKER_MOBILE);
    }

    @Test
    void detect는_SELF_GQL_배치가_아니면_감지_없이_기본_소스를_반환한다() {
        // DATALIKERS 원형은 flat(pk 최상위) — 감지하면 HIKER_MOBILE로 오기록되므로 게이트로 보호
        Map<String, Object> flat = Map.of("username", "a", "pk", "1");
        assertThat(ProfileExtractor.detect(flat, RawSource.DATALIKERS)).isEqualTo(RawSource.DATALIKERS);
        assertThat(ProfileExtractor.detect(flat, RawSource.APIFY_ACTOR)).isEqualTo(RawSource.APIFY_ACTOR);
    }

    @Test
    void detect는_모르는_형태면_기본_소스를_반환한다() {
        Map<String, Object> unknown = Map.of("username", "a", "followersCount", 1L);
        assertThat(ProfileExtractor.detect(unknown, RawSource.SELF_GQL)).isEqualTo(RawSource.SELF_GQL);
    }

    @Test
    void 뷰티_판정_재료가_없거나_공백이면_null() {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("biography", "  ");
        assertThat(ProfileExtractor.fullName(empty, RawSource.LEGACY_ENVELOPE)).isNull();
        assertThat(ProfileExtractor.category(empty, RawSource.LEGACY_ENVELOPE)).isNull();
        assertThat(ProfileExtractor.biography(empty, RawSource.LEGACY_ENVELOPE)).isNull();
    }
}
