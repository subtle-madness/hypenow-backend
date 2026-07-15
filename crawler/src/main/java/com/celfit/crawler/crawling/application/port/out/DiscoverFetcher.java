package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.application.service.CrawlExecutor;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.DiscoverSource;

/** 발굴(키워드→게시물 목록) 소스 추상화. 구현체는 crawl_run 기록까지 책임진다. */
public interface DiscoverFetcher {

    CrawlExecutor.Execution fetch(String keyword, TriggerType trigger);

    DiscoverSource source();

    /** raw_discovery_post.source에 기록할 실제 발굴 출처. */
    RawSource rawSource();
}
