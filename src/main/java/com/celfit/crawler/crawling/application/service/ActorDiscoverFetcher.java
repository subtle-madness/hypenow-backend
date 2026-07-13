package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.application.port.out.DiscoverFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.settings.domain.DiscoverSource;
import org.springframework.stereotype.Component;

/** 기존 Apify 해시태그 액터 경로. */
@Component
public class ActorDiscoverFetcher implements DiscoverFetcher {

    private final CrawlExecutor executor;
    private final SettingsService settings;

    public ActorDiscoverFetcher(CrawlExecutor executor, SettingsService settings) {
        this.executor = executor;
        this.settings = settings;
    }

    @Override
    public CrawlExecutor.Execution fetch(String keyword, TriggerType trigger) {
        return executor.execute(JobName.DISCOVER, trigger, keyword, null,
                Actors.DISCOVERY, ActorInputs.discovery(keyword, settings.resultsLimit()));
    }

    @Override
    public DiscoverSource source() {
        return DiscoverSource.ACTOR;
    }

    @Override
    public RawSource rawSource() {
        return RawSource.APIFY_ACTOR;
    }
}
