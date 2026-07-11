package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.service.CrawlExecutor;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.List;
import java.util.Set;

/** 청크(shortCode 여러 개)의 상세 수집. 청크 전체를 crawl_run 1건으로 감싼다. */
public interface DetailFetcher {
    DetailResult fetch(List<String> shortCodes, ContentType type, TriggerType trigger);
    DetailSource source();
    boolean supports(ContentType type);

    /** 수집 결과 + 오류로 스킵된 shortCode(일시적 실패 — 재시도 대상, GONE 금지). */
    record DetailResult(CrawlExecutor.Execution execution, Set<String> failedShortCodes) {}
}
