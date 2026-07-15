package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;
import org.springframework.stereotype.Component;

/** 기존 Apify 프로필 액터 경로 — 액터 아이템은 이미 원형이라 그대로 반환한다. */
@Component
public class ActorProfileFetcher implements ProfileFetcher {

    private final CrawlExecutor executor;

    public ActorProfileFetcher(CrawlExecutor executor) {
        this.executor = executor;
    }

    @Override
    public CrawlExecutor.Execution fetch(JobName job, List<String> usernames, TriggerType trigger) {
        return executor.execute(job, trigger, null, null,
                Actors.PROFILE, ActorInputs.profiles(usernames));
    }

    @Override
    public ProfileSource source() {
        return ProfileSource.ACTOR;
    }

    @Override
    public RawSource rawSource() {
        return RawSource.APIFY_ACTOR;
    }
}
