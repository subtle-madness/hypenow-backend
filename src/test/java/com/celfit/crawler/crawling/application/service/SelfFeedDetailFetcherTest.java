package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SelfFeedDetailFetcherTest {

    ObjectMapper om = new ObjectMapper();
    DetailMapper mapper = new DetailMapper(om);

    /** get은 부트스트랩 페이지(lsd 포함 HTML), post는 shortcode별 graphql. */
    InstagramWebClient fakeWeb(java.util.function.Function<String, InstagramWebClient.Response> post) {
        return new InstagramWebClient() {
            @Override public Response get(String url) {
                return new Response(200, "<script>\"LSD\",[],{\"token\":\"lsd-abc\"}</script>", Map.of());
            }
            @Override public Response post(String url, String body, Map<String, String> headers) {
                return post.apply(body);
            }
        };
    }

    @Test void 부트스트랩_후_shortCode별_graphql_정규화() {
        var web = fakeWeb(body -> new InstagramWebClient.Response(200,
                "{\"data\":{\"xdt_shortcode_media\":{\"shortcode\":\"SC1\",\"edge_media_preview_like\":{\"count\":9}}}}",
                Map.of()));
        var f = new SelfFeedDetailFetcher(web, null, mapper, Duration.ZERO, "doc123", "PostQuery", om);
        List<Map<String, Object>> out = f.collect(List.of("SC1"), new java.util.LinkedHashSet<>());
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("shortCode", "SC1").containsEntry("likesCount", 9L);
    }

    @Test void 한_shortCode_500이어도_failed집합에_담기고_나머지_보존() {
        var web = fakeWeb(body -> body.contains("BAD")
                ? new InstagramWebClient.Response(500, "", Map.of())
                : new InstagramWebClient.Response(200,
                    "{\"data\":{\"xdt_shortcode_media\":{\"shortcode\":\"OK\"}}}", Map.of()));
        var f = new SelfFeedDetailFetcher(web, null, mapper, Duration.ZERO, "doc123", "PostQuery", om);
        var failed = new java.util.LinkedHashSet<String>();
        List<Map<String, Object>> out = f.collect(List.of("BAD", "OK"), failed);
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("shortCode", "OK");
        assertThat(failed).containsExactly("BAD");
    }
}
