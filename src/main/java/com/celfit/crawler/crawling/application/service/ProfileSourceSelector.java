package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.ProfileSourceSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** profile.source 설정으로 베이스 페처 선택(미존재 시 SELF 폴백) 후 보충 적용. */
@Service
public class ProfileSourceSelector {

    private final Map<ProfileSource, ProfileFetcher> bySource;
    private final ProfileSourceSetting setting;
    private final ProfileSupplementer supplementer;

    public ProfileSourceSelector(List<ProfileFetcher> fetchers, ProfileSourceSetting setting,
                                 ProfileSupplementer supplementer) {
        this.bySource = fetchers.stream().collect(Collectors.toMap(ProfileFetcher::source, Function.identity()));
        this.setting = setting;
        this.supplementer = supplementer;
    }

    public CrawlExecutor.Execution fetchAndSupplement(List<String> usernames, TriggerType trigger) {
        ProfileFetcher f = selected();
        return supplementer.apply(f.fetch(usernames, trigger), f.source());
    }

    /** 직전 fetchAndSupplement()가 실제로 사용한(또는 지금 호출하면 사용할) 소스. */
    public RawSource currentSource() {
        return selected().rawSource();
    }

    private ProfileFetcher selected() {
        ProfileFetcher f = bySource.get(setting.current());
        if (f == null) f = bySource.get(ProfileSource.SELF);
        return f;
    }
}
