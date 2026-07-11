package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.settings.domain.DiscoverSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HikerDiscoverFetcherTest {

    static String media(String code, String user) {
        return """
            {"media":{"code":"%s","taken_at":1781694665,"product_type":"clips",
             "like_count":1,"comment_count":1,"play_count":1,
             "user":{"username":"%s"}}}""".formatted(code, user);
    }

    static String page(String medias, String nextPageId, boolean more) {
        return """
            {"response":{"sections":[{"layout_content":{"medias":[%s]}}],"more_available":%b},
             "next_page_id":%s}""".formatted(medias, more,
                nextPageId == null ? "null" : "\"" + nextPageId + "\"");
    }

    /** executor 목: Supplier를 즉시 실행해 Execution으로 감싼다 (CrawlExecutor 실동작 모사). */
    static List<ApifyResult> capturedResults = new ArrayList<>();

    static CrawlExecutor passthroughExecutor(List<String> capturedLabels) {
        capturedResults.clear();
        CrawlExecutor executor = mock(CrawlExecutor.class);
        when(executor.execute(eq(JobName.DISCOVER), eq(TriggerType.MANUAL), eq(5L), eq("립"),
                any(String.class), any(Supplier.class)))
            .thenAnswer(inv -> {
                capturedLabels.add(inv.getArgument(4));
                Supplier<ApifyResult> work = inv.getArgument(5);
                ApifyResult r = work.get();
                capturedResults.add(r);
                return new CrawlExecutor.Execution(1L, r.items());
            });
        return executor;
    }

    @Test void resultsLimit_채울때까지_페이지_반복() {
        HikerHttp http = path -> path.contains("page_id=P2")
            ? page(media("C3", "u3"), null, false)
            : page(media("C1", "u1") + "," + media("C2", "u2"), "P2", true);
        SettingsService settings = mock(SettingsService.class);
        when(settings.resultsLimit()).thenReturn(3);
        List<String> labels = new ArrayList<>();

        var fetcher = new HikerDiscoverFetcher(http, passthroughExecutor(labels),
                new HikerDiscoveryMapper(new ObjectMapper()), settings);
        var ex = fetcher.fetch(5L, "립", TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(3);
        assertThat(ex.items().get(2).get("shortCode")).isEqualTo("C3");
        assertThat(labels).containsExactly("hiker-hashtag-top");
        assertThat(capturedResults.get(0).runId()).isEqualTo("pages=2");  // 비용 추적용 페이지 수
        assertThat(fetcher.source()).isEqualTo(DiscoverSource.HIKER);
    }

    @Test void limit_도달하면_다음_페이지_안_부름() {
        List<String> calls = new ArrayList<>();
        HikerHttp http = path -> {
            calls.add(path);
            return page(media("C1", "u1") + "," + media("C2", "u2"), "P2", true);
        };
        SettingsService settings = mock(SettingsService.class);
        when(settings.resultsLimit()).thenReturn(2);  // 첫 페이지로 충족

        var fetcher = new HikerDiscoverFetcher(http, passthroughExecutor(new ArrayList<>()),
                new HikerDiscoveryMapper(new ObjectMapper()), settings);
        var ex = fetcher.fetch(5L, "립", TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(2);
        assertThat(calls).hasSize(1);  // page_id 요청 없음
    }

    @Test void 빈페이지_more_available_true_무한루프_MAX_PAGES에서_종료() {
        List<String> calls = new ArrayList<>();
        HikerHttp http = path -> { calls.add(path); return page("", "P", true); };
        SettingsService settings = mock(SettingsService.class);
        when(settings.resultsLimit()).thenReturn(100);

        var fetcher = new HikerDiscoverFetcher(http, passthroughExecutor(new ArrayList<>()),
                new HikerDiscoveryMapper(new ObjectMapper()), settings);
        var ex = fetcher.fetch(5L, "립", TriggerType.MANUAL);

        assertThat(ex.items()).isEmpty();
        assertThat(calls).hasSize(HikerDiscoverFetcher.MAX_PAGES);
    }

    @Test void 키워드는_URL인코딩된다() {
        List<String> calls = new ArrayList<>();
        HikerHttp http = path -> { calls.add(path); return page(media("C1", "u1"), null, false); };
        SettingsService settings = mock(SettingsService.class);
        when(settings.resultsLimit()).thenReturn(10);

        new HikerDiscoverFetcher(http, passthroughExecutor(new ArrayList<>()),
                new HikerDiscoveryMapper(new ObjectMapper()), settings)
            .fetch(5L, "립", TriggerType.MANUAL);

        assertThat(calls.get(0)).startsWith("/v2/hashtag/medias/top?name=%EB%A6%BD");
    }
}
