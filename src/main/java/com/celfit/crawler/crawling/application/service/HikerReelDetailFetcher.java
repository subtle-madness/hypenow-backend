package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.DetailFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** HikerAPI 릴스 상세 — shortCode별 /v2/media/info/by/code 단건 호출(per-item skip). */
@Component
public class HikerReelDetailFetcher implements DetailFetcher {

    static final String LABEL = "detail-hiker-reels";
    private static final Logger log = LoggerFactory.getLogger(HikerReelDetailFetcher.class);

    private final HikerHttp http;
    private final CrawlExecutor executor;
    private final DetailMapper mapper;

    public HikerReelDetailFetcher(HikerHttp http, CrawlExecutor executor, DetailMapper mapper) {
        this.http = http;
        this.executor = executor;
        this.mapper = mapper;
    }

    @Override
    public DetailFetcher.DetailResult fetch(List<String> shortCodes, ContentType type, TriggerType trigger) {
        java.util.Set<String> failed = new java.util.LinkedHashSet<>();
        CrawlExecutor.Execution ex = executor.execute(JobName.COLLECT, trigger, null, null, LABEL,
                () -> new ApifyResult(null, collect(shortCodes, failed)));
        return new DetailFetcher.DetailResult(ex, failed);
    }

    List<Map<String, Object>> collect(List<String> shortCodes, java.util.Set<String> failed) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String sc : shortCodes) {
            try {
                Map<String, Object> d = mapper.fromHikerMedia(http.get("/v2/media/info/by/code?code=" + sc));
                if (d.get("shortCode") != null) out.add(d);
            } catch (ApifyException e) {
                failed.add(sc);   // 일시적 실패 — 재시도 대상(GONE 금지)
                log.warn("릴스 상세 실패, 스킵: {} ({})", sc, e.getMessage());
            }
        }
        return out;
    }

    @Override public DetailSource source() { return DetailSource.HIKER; }

    @Override public boolean supports(ContentType type) { return type == ContentType.REELS; }
}
