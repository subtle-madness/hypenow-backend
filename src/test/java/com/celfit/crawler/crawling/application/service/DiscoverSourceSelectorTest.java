package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.application.port.out.DiscoverFetcher;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.DiscoverSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSourceSettingTest;
import com.celfit.crawler.settings.domain.DiscoverSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscoverSourceSelectorTest {

    static DiscoverFetcher fetcher(DiscoverSource src, String marker) {
        return new DiscoverFetcher() {
            @Override public CrawlExecutor.Execution fetch(String k, TriggerType t) {
                return new CrawlExecutor.Execution(1L, List.of(Map.of("shortCode", marker)));
            }
            @Override public DiscoverSource source() { return src; }
        };
    }

    @Test void 설정된_소스의_페처를_고른다() {
        var setting = new DiscoverSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.update(DiscoverSource.ACTOR);
        var sel = new DiscoverSourceSelector(
            List.of(fetcher(DiscoverSource.ACTOR, "actor"), fetcher(DiscoverSource.HIKER, "hiker")), setting);
        var ex = sel.fetch("립", TriggerType.MANUAL);
        assertThat(ex.items().get(0).get("shortCode")).isEqualTo("actor");
    }

    @Test void 미등록_소스면_HIKER_폴백() {
        var setting = new DiscoverSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.update(DiscoverSource.ACTOR);  // ACTOR 페처 미등록
        var sel = new DiscoverSourceSelector(List.of(fetcher(DiscoverSource.HIKER, "hiker")), setting);
        var ex = sel.fetch("립", TriggerType.MANUAL);
        assertThat(ex.items().get(0).get("shortCode")).isEqualTo("hiker");
    }
}
