package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.settings.domain.DiscoverSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ActorDiscoverFetcherTest {

    @Test void 기존_액터_경로로_위임한다() {
        CrawlExecutor executor = mock(CrawlExecutor.class);
        SettingsService settings = mock(SettingsService.class);
        when(settings.resultsLimit()).thenReturn(7);
        var expected = new CrawlExecutor.Execution(1L, List.of(Map.of("shortCode", "sc1")));
        when(executor.execute(eq(JobName.DISCOVER), eq(TriggerType.MANUAL), eq("립"), isNull(),
                eq(Actors.DISCOVERY), org.mockito.ArgumentMatchers.<Map<String, Object>>any()))
                .thenReturn(expected);

        var fetcher = new ActorDiscoverFetcher(executor, settings);
        var ex = fetcher.fetch("립", TriggerType.MANUAL);

        assertThat(ex).isSameAs(expected);
        assertThat(fetcher.source()).isEqualTo(DiscoverSource.ACTOR);
        assertThat(fetcher.rawSource()).isEqualTo(RawSource.APIFY_ACTOR);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(executor).execute(eq(JobName.DISCOVER), eq(TriggerType.MANUAL), eq("립"), isNull(),
                eq(Actors.DISCOVERY), input.capture());
        assertThat(input.getValue()).containsEntry("resultsLimit", 7).containsEntry("hashtags", List.of("립"));
    }
}
