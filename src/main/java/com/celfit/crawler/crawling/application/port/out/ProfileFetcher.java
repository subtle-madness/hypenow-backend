package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.application.service.CrawlExecutor;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;

/** 계정 여러 개의 프로필 수집. 전체를 crawl_run 1건으로 감싼다. items[i]에는 최소 username·followersCount·userId 포함. */
public interface ProfileFetcher {
    CrawlExecutor.Execution fetch(List<String> usernames, TriggerType trigger);
    ProfileSource source();
}
