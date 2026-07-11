package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.DiscoverFetcher;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.DiscoverSourceSetting;
import com.celfit.crawler.settings.domain.DiscoverSource;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** discover.source 설정으로 발굴 페처 선택(미존재 시 HIKER 폴백). */
@Service
public class DiscoverSourceSelector {

    private final Map<DiscoverSource, DiscoverFetcher> bySource;
    private final DiscoverSourceSetting setting;

    public DiscoverSourceSelector(List<DiscoverFetcher> fetchers, DiscoverSourceSetting setting) {
        this.bySource = fetchers.stream().collect(Collectors.toMap(DiscoverFetcher::source, Function.identity()));
        this.setting = setting;
    }

    public CrawlExecutor.Execution fetch(long categoryId, String keyword, TriggerType trigger) {
        DiscoverFetcher f = bySource.get(setting.current());
        if (f == null) f = bySource.get(DiscoverSource.HIKER);
        return f.fetch(categoryId, keyword, trigger);
    }
}
