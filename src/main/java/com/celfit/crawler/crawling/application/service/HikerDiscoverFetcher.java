package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.DiscoverFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.settings.domain.DiscoverSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * HikerAPI 해시태그 인기(/v2/hashtag/medias/top) 발굴.
 * resultsLimit 이상 모일 때까지 next_page_id로 반복(페이지 단위 과수집 허용).
 * 페이지 중간 실패는 예외 전파 — 부분 발굴을 성공으로 기록하지 않는다(키워드 단위 재시도).
 */
@Component
public class HikerDiscoverFetcher implements DiscoverFetcher {

    static final String LABEL = "hiker-hashtag-top";

    private final HikerHttp http;
    private final CrawlExecutor executor;
    private final HikerDiscoveryMapper mapper;
    private final SettingsService settings;

    public HikerDiscoverFetcher(HikerHttp http, CrawlExecutor executor,
                                HikerDiscoveryMapper mapper, SettingsService settings) {
        this.http = http;
        this.executor = executor;
        this.mapper = mapper;
        this.settings = settings;
    }

    @Override
    public CrawlExecutor.Execution fetch(long categoryId, String keyword, TriggerType trigger) {
        return executor.execute(JobName.DISCOVER, trigger, categoryId, keyword, LABEL,
                () -> new ApifyResult(null, collect(keyword)));
    }

    private List<Map<String, Object>> collect(String keyword) {
        int limit = settings.resultsLimit();
        String enc = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        List<Map<String, Object>> out = new ArrayList<>();
        String pageId = null;
        while (true) {
            String path = "/v2/hashtag/medias/top?name=" + enc
                    + (pageId == null ? "" : "&page_id=" + URLEncoder.encode(pageId, StandardCharsets.UTF_8));
            HikerDiscoveryMapper.Page page = mapper.parse(http.get(path));
            out.addAll(page.items());
            pageId = page.nextPageId();
            if (out.size() >= limit || !page.moreAvailable() || pageId == null || pageId.isBlank()) break;
        }
        return out;
    }

    @Override
    public DiscoverSource source() {
        return DiscoverSource.HIKER;
    }
}
