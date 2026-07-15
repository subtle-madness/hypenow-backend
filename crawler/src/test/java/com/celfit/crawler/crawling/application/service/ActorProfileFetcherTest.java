package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActorProfileFetcherTest {

    @Test void source는_ACTOR_rawSource는_APIFY_ACTOR() {
        var f = new ActorProfileFetcher(mock(CrawlExecutor.class));
        assertThat(f.source()).isEqualTo(ProfileSource.ACTOR);
        assertThat(f.rawSource()).isEqualTo(RawSource.APIFY_ACTOR);
    }

    @Test void 액터결과를_원형_그대로_반환() {
        CrawlExecutor exec = mock(CrawlExecutor.class);
        var raw = new CrawlExecutor.Execution(7L, List.of(
            new java.util.HashMap<>(Map.of("username","tem.duck","followersCount",256169,"id","74756186520"))));
        when(exec.execute(any(), any(), any(), any(), any(), any(Map.class))).thenReturn(raw);

        var f = new ActorProfileFetcher(exec);
        var ex = f.fetch(JobName.QUALIFY, List.of("tem.duck"), TriggerType.MANUAL);

        assertThat(ex).isSameAs(raw); // 그대로 전달 — 별도 변환 없음
        assertThat(ProfileExtractor.username(ex.items().get(0), RawSource.APIFY_ACTOR)).isEqualTo("tem.duck");
        assertThat(ProfileExtractor.followers(ex.items().get(0), RawSource.APIFY_ACTOR)).isEqualTo(256169L);
    }
}
