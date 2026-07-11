package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.DetailFetcher;
import com.celfit.crawler.settings.application.service.DetailSourceSetting;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** detail.<type>.source 설정으로 타입별 상세 fetcher 선택. 미지원/미존재 시 ACTOR 폴백. */
@Service
public class DetailSourceSelector {

    private final List<DetailFetcher> fetchers;
    private final DetailSourceSetting setting;

    public DetailSourceSelector(List<DetailFetcher> fetchers, DetailSourceSetting setting) {
        this.fetchers = fetchers;
        this.setting = setting;
    }

    public DetailFetcher forType(ContentType type) {
        DetailSource src = setting.sourceFor(type);
        return pick(type, src).orElseGet(() -> pick(type, DetailSource.ACTOR).orElseThrow(
                () -> new IllegalStateException("상세 fetcher 없음: " + type)));
    }

    private Optional<DetailFetcher> pick(ContentType type, DetailSource src) {
        return fetchers.stream().filter(f -> f.source() == src && f.supports(type)).findFirst();
    }
}
