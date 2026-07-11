package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.domain.ShortCodes;
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
        // 요청 바디엔 media_id(디코드된 pk)가 들어가고, 응답은 비로그인 xig_polaris_media
        var web = fakeWeb(body -> {
            assertThat(body).contains("media_id").contains(ShortCodes.mediaId("DamKgsggWef"));
            return new InstagramWebClient.Response(200,
                "{\"data\":{\"xig_polaris_media\":{\"if_not_gated_logged_out\":{"
                    + "\"code\":\"DamKgsggWef\",\"like_count\":9,\"comment_count\":2}}}}", Map.of());
        });
        var f = new SelfFeedDetailFetcher(web, null, mapper, Duration.ZERO, "doc123", "PostQuery", om);
        List<Map<String, Object>> out = f.collect(List.of("DamKgsggWef"), new java.util.LinkedHashSet<>());
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("shortCode", "DamKgsggWef").containsEntry("likesCount", 9L);
    }

    @Test void 한_shortCode_500이어도_failed집합에_담기고_나머지_보존() {
        String badId = ShortCodes.mediaId("BADcode");
        var web = fakeWeb(body -> body.contains(badId)
                ? new InstagramWebClient.Response(500, "", Map.of())
                : new InstagramWebClient.Response(200,
                    "{\"data\":{\"xig_polaris_media\":{\"if_not_gated_logged_out\":{\"code\":\"OKcode11\"}}}}", Map.of()));
        var f = new SelfFeedDetailFetcher(web, null, mapper, Duration.ZERO, "doc123", "PostQuery", om);
        var failed = new java.util.LinkedHashSet<String>();
        List<Map<String, Object>> out = f.collect(List.of("BADcode", "OKcode11"), failed);
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("shortCode", "OKcode11");
        assertThat(failed).containsExactly("BADcode");
    }

    @Test void 게이팅된_게시물은_failed로_재시도() {
        var web = fakeWeb(body -> new InstagramWebClient.Response(200,
                "{\"data\":{\"xig_polaris_media\":{\"gating_ruling\":{\"g\":1},\"if_not_gated_logged_out\":null}}}", Map.of()));
        var f = new SelfFeedDetailFetcher(web, null, mapper, Duration.ZERO, "doc123", "PostQuery", om);
        var failed = new java.util.LinkedHashSet<String>();
        List<Map<String, Object>> out = f.collect(List.of("Gated123"), failed);
        assertThat(out).isEmpty();
        assertThat(failed).containsExactly("Gated123");   // 게이트는 삭제 아님 → GONE 대신 재시도
    }
}
