package com.celfit.crawler.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.settings.domain.ProxySource;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class ProxySourceSettingTest {

    @Test void 미설정이면_APIFY_기본값() {
        var setting = new ProxySourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        assertThat(setting.current()).isEqualTo(ProxySource.APIFY);
    }

    @Test void 저장한_소스를_돌려준다() {
        var setting = new ProxySourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.update(ProxySource.DATAIMPULSE_MOBILE);
        assertThat(setting.current()).isEqualTo(ProxySource.DATAIMPULSE_MOBILE);
    }

    @Test void 이상한_값이면_APIFY로_폴백() {
        var store = new HashMap<String, String>();
        store.put("proxy.source", "GARBAGE");
        var setting = new ProxySourceSetting(ProfileSourceSettingTest.fakeRepo(store));
        assertThat(setting.current()).isEqualTo(ProxySource.APIFY);
    }
}
