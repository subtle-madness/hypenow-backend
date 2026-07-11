package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DetailMapperTest {

    DetailMapper mapper = new DetailMapper(new ObjectMapper());

    @Test void hiker_릴스_미디어_정규화() {
        String json = """
            {"code":"DKdNETtTTz_","caption_text":"광고 아님","like_count":19594,
             "comment_count":42,"play_count":88123,"is_paid_partnership":true}""";
        Map<String, Object> d = mapper.fromHikerMedia(json);
        assertThat(d.get("shortCode")).isEqualTo("DKdNETtTTz_");
        assertThat(d.get("caption")).isEqualTo("광고 아님");
        assertThat(d.get("likesCount")).isEqualTo(19594L);
        assertThat(d.get("commentsCount")).isEqualTo(42L);
        assertThat(d.get("videoPlayCount")).isEqualTo(88123L);
        assertThat(d.get("isPaidPartnership")).isEqualTo(true);
        assertThat(d).containsKey("_rawDetail");
    }

    @Test void hiker_media_or_ad_래퍼_언랩() {
        // 실제 /v2/media/info/by/code 응답은 미디어를 media_or_ad로 감싼다
        String json = """
            {"media_or_ad":{"code":"Cr8TkbLrIZU","caption_text":"안녕",
             "like_count":729,"comment_count":20,"play_count":19666,"is_paid_partnership":false}}""";
        Map<String, Object> d = mapper.fromHikerMedia(json);
        assertThat(d.get("shortCode")).isEqualTo("Cr8TkbLrIZU");
        assertThat(d.get("likesCount")).isEqualTo(729L);
        assertThat(d.get("videoPlayCount")).isEqualTo(19666L);
        assertThat(d).containsKey("_rawDetail");
    }

    @Test void self_피드_graphql_정규화() {
        // 실제 응답은 {data:{xdt_shortcode_media:{...}}} 래핑
        String json = """
            {"data":{"xdt_shortcode_media":{
              "shortcode":"DShi4OoEsoD",
              "edge_media_to_caption":{"edges":[{"node":{"text":"#협찬 캡션"}}]},
              "edge_media_preview_like":{"count":720},
              "edge_media_to_comment":{"count":15},
              "is_paid_partnership":false}}}""";
        Map<String, Object> d = mapper.fromSelfGraphql(json);
        assertThat(d.get("shortCode")).isEqualTo("DShi4OoEsoD");
        assertThat(d.get("caption")).isEqualTo("#협찬 캡션");
        assertThat(d.get("likesCount")).isEqualTo(720L);
        assertThat(d.get("commentsCount")).isEqualTo(15L);
        assertThat(d.get("videoPlayCount")).isNull();   // 피드=조회수 없음
        assertThat(d).containsKey("_rawDetail");
    }

    @Test void actor_아이템은_그대로_통과() {
        Map<String, Object> item = new java.util.HashMap<>(Map.of(
            "shortCode", "ABC", "caption", "x", "likesCount", 5, "commentsCount", 1));
        assertThat(mapper.fromActorItem(item)).isSameAs(item);
    }
}
