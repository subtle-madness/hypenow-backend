package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HikerDiscoveryMapperTest {

    HikerDiscoveryMapper mapper = new HikerDiscoveryMapper(new ObjectMapper());

    // 실측 축약: medias 1개(릴스) + fill_items 1개(code 없는 캐러셀 조각) + one_by_two_item 1개(피드)
    static final String JSON = """
        {"response":{"sections":[
          {"layout_content":{"medias":[{"media":{
            "pk":"1","code":"DZr1AvEMT0M","taken_at":1781694665,"product_type":"clips",
            "like_count":1469,"comment_count":32,"play_count":108290,
            "user":{"pk":"76739063345","username":"owysim"}}}]}},
          {"layout_content":{"fill_items":[{"media":{
            "pk":"2","taken_at":1781000000,"product_type":"carousel_item"}}]}},
          {"layout_content":{"one_by_two_item":{"clips":{"items":[{"media":{
            "pk":"3","code":"DUpnobjj653","taken_at":1780000000,"product_type":"feed",
            "like_count":50,"comment_count":3,
            "user":{"pk":"9","username":"pink._.soodal"}}}]}}}}
        ],"more_available":true,"next_max_id":"abc"},"next_page_id":"PAGE2"}""";

    @Test void 정규화_4필드와_부가카운트() {
        var page = mapper.parse(JSON);
        assertThat(page.items()).hasSize(2);  // 캐러셀 조각(code 없음)은 스킵
        Map<String, Object> first = page.items().get(0);
        assertThat(first)
            .containsEntry("shortCode", "DZr1AvEMT0M")
            .containsEntry("timestamp", "2026-06-17T11:11:05Z")   // 1781694665 epoch → ISO
            .containsEntry("ownerUsername", "owysim")
            .containsEntry("productType", "clips")
            .containsEntry("likesCount", 1469L)
            .containsEntry("commentsCount", 32L)
            .containsEntry("videoPlayCount", 108290L);
        assertThat(first).containsKey("_rawMedia");  // 원본 통째 보존
        assertThat(page.items().get(1)).containsEntry("ownerUsername", "pink._.soodal");
    }

    @Test void 페이지네이션_커서() {
        var page = mapper.parse(JSON);
        assertThat(page.nextPageId()).isEqualTo("PAGE2");
        assertThat(page.moreAvailable()).isTrue();
    }

    @Test void username_없는_미디어는_스킵() {
        String json = """
            {"response":{"sections":[{"layout_content":{"medias":[{"media":{
              "pk":"1","code":"X","taken_at":1781694665}}]}}],"more_available":false}}""";
        var page = mapper.parse(json);
        assertThat(page.items()).isEmpty();
        assertThat(page.moreAvailable()).isFalse();
        assertThat(page.nextPageId()).isNull();
    }

    @Test void 깨진_JSON이면_ApifyException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapper.parse("{broken"))
            .isInstanceOf(com.celfit.crawler.crawling.application.port.out.ApifyException.class);
    }
}
