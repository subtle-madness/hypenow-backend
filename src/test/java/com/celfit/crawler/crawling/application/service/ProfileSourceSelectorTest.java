package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.ProfileSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSourceSettingTest;
import com.celfit.crawler.settings.application.service.ProfileSupplementSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfileSourceSelectorTest {

    static ProfileFetcher fetcher(ProfileSource src, String marker) {
        return new ProfileFetcher() {
            @Override public CrawlExecutor.Execution fetch(List<String> u, TriggerType t) {
                Map<String, Object> item = new HashMap<>(Map.of("username", marker, "followersCount", 1L, "userId", "1"));
                return new CrawlExecutor.Execution(1L, List.of(item));
            }
            @Override public ProfileSource source() { return src; }
        };
    }

    ProfileSupplementer noopSupplementer() {
        var s = new ProfileSupplementSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>())); // 둘다 false
        return new ProfileSupplementer(null, null, s); // false라 보충 진입 안 함
    }

    @Test void 설정된_소스의_페처를_고른다() {
        var setting = new ProfileSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.update(ProfileSource.HIKER_MOBILE);
        var sel = new ProfileSourceSelector(
            List.of(fetcher(ProfileSource.SELF, "self"), fetcher(ProfileSource.HIKER_MOBILE, "mobile")),
            setting, noopSupplementer());
        var ex = sel.fetchAndSupplement(List.of("x"), TriggerType.MANUAL);
        assertThat(ex.items().get(0).get("username")).isEqualTo("mobile");
    }

    @Test void 미등록_소스면_SELF_폴백() {
        var setting = new ProfileSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.update(ProfileSource.ACTOR); // ACTOR 페처 미등록
        var sel = new ProfileSourceSelector(
            List.of(fetcher(ProfileSource.SELF, "self")), setting, noopSupplementer());
        var ex = sel.fetchAndSupplement(List.of("x"), TriggerType.MANUAL);
        assertThat(ex.items().get(0).get("username")).isEqualTo("self");
    }
}
