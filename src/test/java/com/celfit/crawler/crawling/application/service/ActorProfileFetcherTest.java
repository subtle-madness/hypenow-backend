package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActorProfileFetcherTest {

    @Test void source는_ACTOR() {
        var f = new ActorProfileFetcher(mock(CrawlExecutor.class), new ProfileMapper(new tools.jackson.databind.ObjectMapper()));
        assertThat(f.source()).isEqualTo(ProfileSource.ACTOR);
    }

    @Test void 액터결과를_정규화해_반환() {
        CrawlExecutor exec = mock(CrawlExecutor.class);
        var raw = new CrawlExecutor.Execution(7L, List.of(
            new java.util.HashMap<>(Map.of("username","tem.duck","followersCount",256169,"id","74756186520"))));
        when(exec.execute(any(), any(), any(), any(), any(), any(Map.class))).thenReturn(raw);

        var f = new ActorProfileFetcher(exec, new ProfileMapper(new tools.jackson.databind.ObjectMapper()));
        var ex = f.fetch(List.of("tem.duck"), TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(1);
        assertThat(ex.items().get(0).get("followersCount")).isEqualTo(256169L);
        assertThat(ex.items().get(0).get("userId")).isEqualTo("74756186520");
    }
}
