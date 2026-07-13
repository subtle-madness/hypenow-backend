package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.application.service.CrawlExecutor;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;

/** 계정 여러 개의 프로필 수집. 전체를 crawl_run 1건으로 감싼다. items[i]는 응답 원형 그대로(정규화 없음). */
public interface ProfileFetcher {
    CrawlExecutor.Execution fetch(List<String> usernames, TriggerType trigger);
    ProfileSource source();

    /** items[i]의 원형 형태를 나타내는 RawSource — ProfileExtractor 경로 선택에 사용. */
    RawSource rawSource();
}
