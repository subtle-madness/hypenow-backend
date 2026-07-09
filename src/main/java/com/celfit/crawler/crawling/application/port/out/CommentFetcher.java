package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.application.service.CrawlExecutor;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.List;

/** 청크(포스트 여러 개)의 댓글 수집. 청크 전체를 crawl_run 1건으로 감싼다. */
public interface CommentFetcher {
    CrawlExecutor.Execution fetch(List<String> shortCodes, int limit, TriggerType trigger);
    CommentSource source();
}
