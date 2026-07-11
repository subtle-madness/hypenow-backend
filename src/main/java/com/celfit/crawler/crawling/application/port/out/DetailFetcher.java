package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.service.CrawlExecutor;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.List;

/** 청크(shortCode 여러 개)의 상세 수집. 청크 전체를 crawl_run 1건으로 감싼다. */
public interface DetailFetcher {
    CrawlExecutor.Execution fetch(List<String> shortCodes, ContentType type, TriggerType trigger);
    DetailSource source();
    boolean supports(ContentType type);
}
