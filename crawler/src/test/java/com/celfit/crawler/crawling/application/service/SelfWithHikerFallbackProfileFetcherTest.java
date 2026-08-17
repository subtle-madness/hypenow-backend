package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.application.port.out.PaidCallCounter;
import com.celfit.crawler.crawling.application.port.out.NotFoundException;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SelfWithHikerFallbackProfileFetcherTest {

    static CrawlExecutor passthrough() { return SelfProfileFetcherTest.passthroughExecutor(); }
    ObjectMapper om = new ObjectMapper();

    /** bugged 집합은 400, 나머지는 SELF_GQL 원형 200. */
    static InstagramWebClient webWith400For(Set<String> bugged) {
        return new InstagramWebClient() {
            @Override public Response get(String url) {
                String u = url.substring(url.lastIndexOf('=') + 1);
                if (bugged.contains(u)) return new Response(400, "{\"status\":\"fail\"}", Map.of());
                return new Response(200,
                        "{\"data\":{\"user\":{\"username\":\"" + u + "\",\"id\":\"1\"}}}", Map.of());
            }
            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
    }

    SelfWithHikerFallbackProfileFetcher fetcher(InstagramWebClient web, HikerHttp http) {
        var self = new SelfProfileFetcher(web, passthrough(), om, Duration.ZERO);
        var hiker = new HikerMobileProfileFetcher(http, passthrough(), new PaidCallCounter(), om);
        return new SelfWithHikerFallbackProfileFetcher(self, hiker, passthrough());
    }

    @Test void 소스는_SELF_HIKER_FALLBACK_기본_rawSource는_SELF_GQL() {
        var f = fetcher(webWith400For(Set.of()), path -> { throw new AssertionError("호출되면 안됨"); });
        assertThat(f.source()).isEqualTo(ProfileSource.SELF_HIKER_FALLBACK);
        assertThat(f.rawSource()).isEqualTo(RawSource.SELF_GQL);
    }

    @Test void 자체조회_400_계정만_Hiker로_폴백되어_병합된다() {
        AtomicInteger hikerCalls = new AtomicInteger();
        HikerHttp http = path -> {
            hikerCalls.incrementAndGet();
            assertThat(path).contains("/v2/user/by/username").contains("username=bugged");
            return "{\"user\":{\"username\":\"bugged\",\"pk\":\"2\"}}";
        };
        var f = fetcher(webWith400For(Set.of("bugged")), http);

        var ex = f.fetch(JobName.QUALIFY, List.of("ok", "bugged"), TriggerType.MANUAL);

        assertThat(hikerCalls.get()).isEqualTo(1);   // 정상 계정은 Hiker 미호출
        assertThat(ex.items()).hasSize(2);
        // 아이템별 원형이 섞여 있고, detect로 구분된다
        var sources = ex.items().stream().map(i -> ProfileExtractor.detect(i, RawSource.SELF_GQL)).toList();
        assertThat(sources).containsExactlyInAnyOrder(RawSource.SELF_GQL, RawSource.HIKER_MOBILE);
    }

    @Test void 자체조회_400이_없으면_Hiker를_호출하지_않는다() {
        var f = fetcher(webWith400For(Set.of()), path -> { throw new AssertionError("호출되면 안됨"); });

        var ex = f.fetch(JobName.QUALIFY, List.of("a", "b"), TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(2);
    }

    @Test void 폴백_조회의_404는_notFound로_병합된다() {
        // SELF에서 400이 났지만 Hiker 기준으로는 계정 소멸 — 소프트 딜리트 경로로 종결돼야 한다
        HikerHttp http = path -> { throw new NotFoundException("Hiker HTTP 404"); };
        var f = fetcher(webWith400For(Set.of("gone")), http);

        var ex = f.fetch(JobName.QUALIFY, List.of("ok", "gone"), TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(1);
        assertThat(ex.notFound()).containsExactly("gone");
    }
}
