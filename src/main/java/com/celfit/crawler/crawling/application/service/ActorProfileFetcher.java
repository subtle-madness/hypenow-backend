package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 기존 Apify 프로필 액터 경로. */
@Component
public class ActorProfileFetcher implements ProfileFetcher {

    private final CrawlExecutor executor;
    private final ProfileMapper mapper;

    public ActorProfileFetcher(CrawlExecutor executor, ProfileMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    @Override
    public CrawlExecutor.Execution fetch(List<String> usernames, TriggerType trigger) {
        CrawlExecutor.Execution raw = executor.execute(JobName.QUALIFY, trigger, null, null,
                Actors.PROFILE, ActorInputs.profiles(usernames));
        List<Map<String, Object>> mapped = raw.items().stream().map(mapper::fromActorItem).toList();
        return new CrawlExecutor.Execution(raw.runId(), mapped);
    }

    @Override
    public ProfileSource source() {
        return ProfileSource.ACTOR;
    }
}
