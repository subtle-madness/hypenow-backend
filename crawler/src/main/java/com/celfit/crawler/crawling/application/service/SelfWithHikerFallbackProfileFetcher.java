package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SELF 베이스 + 400 폴백 컴포지트 — web_profile_info로 배치를 돌리고, IP 무관 HTTP 400
 * (비즈니스 카테고리 버그)이 난 계정만 HikerAPI /v2/user/by/username으로 2차 조회해 병합한다.
 * 호출자가 ex.runId()로 raw를 저장하므로 crawl_run은 컴포지트 라벨로 1건만 만든다 —
 * 두 페처의 fetch()가 아니라 collect 로직을 직접 호출하는 이유. 혼합 배치의 아이템별
 * 실제 소스는 ProfileExtractor.detect로 구분한다.
 */
@Component
public class SelfWithHikerFallbackProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-self-hiker";
    private static final Logger log = LoggerFactory.getLogger(SelfWithHikerFallbackProfileFetcher.class);

    private final SelfProfileFetcher self;
    private final HikerMobileProfileFetcher hiker;
    private final CrawlExecutor executor;

    public SelfWithHikerFallbackProfileFetcher(SelfProfileFetcher self, HikerMobileProfileFetcher hiker,
                                               CrawlExecutor executor) {
        this.self = self;
        this.hiker = hiker;
        this.executor = executor;
    }

    @Override
    public CrawlExecutor.Execution fetch(JobName job, List<String> usernames, TriggerType trigger) {
        return executor.execute(job, trigger, null, null, LABEL, () -> collect(usernames));
    }

    private ApifyResult collect(List<String> usernames) {
        List<String> badRequest = new ArrayList<>();
        ApifyResult base = self.collect(usernames, badRequest);
        if (badRequest.isEmpty()) return base;
        log.info("SELF 400 {}건 — Hiker 폴백: {}", badRequest.size(), badRequest);
        ApifyResult fallback = hiker.collect(badRequest);
        List<Map<String, Object>> items = new ArrayList<>(base.items());
        items.addAll(fallback.items());
        List<String> notFound = new ArrayList<>(base.notFound());
        notFound.addAll(fallback.notFound());
        return new ApifyResult(null, items, notFound);
    }

    @Override
    public ProfileSource source() {
        return ProfileSource.SELF_HIKER_FALLBACK;
    }

    /** 혼합 배치의 기본 소스 — 아이템별 실제 소스는 ProfileExtractor.detect로 구분한다. */
    @Override
    public RawSource rawSource() {
        return RawSource.SELF_GQL;
    }
}
