package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.application.port.out.DetailFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.ShortCodes;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.List;
import org.springframework.stereotype.Component;

/** 기존 Apify 상세 액터 래핑 — 타입별 액터/URL 선택. crawl_run 라벨은 액터 id(현행 유지). */
@Component
public class ActorDetailFetcher implements DetailFetcher {

    private final CrawlExecutor executor;

    public ActorDetailFetcher(CrawlExecutor executor) {
        this.executor = executor;
    }

    @Override
    public DetailFetcher.DetailResult fetch(List<String> shortCodes, ContentType type, TriggerType trigger) {
        String actor = actorFor(type);
        CrawlExecutor.Execution ex = executor.execute(JobName.COLLECT, trigger, null, null, actor,
                ActorInputs.detailUrls(urlsFor(shortCodes, type)));
        return new DetailFetcher.DetailResult(ex, java.util.Set.of());
    }

    String actorFor(ContentType type) {
        return type == ContentType.REELS ? Actors.DETAIL_REELS : Actors.DETAIL_FEED;
    }

    List<String> urlsFor(List<String> shortCodes, ContentType type) {
        return shortCodes.stream()
                .map(sc -> type == ContentType.REELS ? ShortCodes.reelUrl(sc) : ShortCodes.postUrl(sc))
                .toList();
    }

    @Override public DetailSource source() { return DetailSource.ACTOR; }

    @Override public boolean supports(ContentType type) { return true; }
}
