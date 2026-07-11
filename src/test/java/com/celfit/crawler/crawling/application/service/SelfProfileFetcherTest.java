package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SelfProfileFetcherTest {

    // CrawlExecutor의 Supplier 오버로드만 흉내내는 최소 스텁 (spy 대신 서브클래스)
    static CrawlExecutor passthroughExecutor() {
        return new CrawlExecutor(null, null, null, null) {
            @Override public Execution execute(com.celfit.crawler.crawling.domain.JobName job,
                    TriggerType t, Long c, String k, String actorId, Supplier<ApifyResult> work) {
                ApifyResult r = work.get();
                return new Execution(1L, r.items());
            }
        };
    }

    private static InstagramWebClient webReturning(int status, String body) {
        return new InstagramWebClient() {
            @Override public Response get(String url) {
                return new Response(status, body, Map.of());
            }

            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException("web_profile_info는 GET만 사용");
            }
        };
    }

    @Test void source는_SELF() {
        var f = new SelfProfileFetcher(webReturning(200, null), passthroughExecutor(),
                new ProfileMapper(new ObjectMapper()), Duration.ZERO);
        assertThat(f.source()).isEqualTo(ProfileSource.SELF);
    }

    @Test void 각_username마다_web_profile_info_호출후_정규화() {
        InstagramWebClient web = webReturning(200,
            """
            {"data":{"user":{"username":"beauty.e.ze","id":"74851841915","edge_followed_by":{"count":2369}}}}""");
        var f = new SelfProfileFetcher(web, passthroughExecutor(),
                new ProfileMapper(new ObjectMapper()), Duration.ZERO);

        var ex = f.fetch(List.of("beauty.e.ze"), TriggerType.MANUAL);
        assertThat(ex.items()).hasSize(1);
        assertThat(ex.items().get(0).get("username")).isEqualTo("beauty.e.ze");
        assertThat(ex.items().get(0).get("followersCount")).isEqualTo(2369L);
    }

    @Test void 상태코드가_200이_아니면_스킵() {
        InstagramWebClient web = webReturning(404, "not found");
        var f = new SelfProfileFetcher(web, passthroughExecutor(),
                new ProfileMapper(new ObjectMapper()), Duration.ZERO);

        var ex = f.fetch(List.of("ghost"), TriggerType.MANUAL);
        assertThat(ex.items()).isEmpty();
    }
}
