package com.celfit.crawler.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.settings.domain.ReelsSource;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class ReelsSourceSettingTest {

    @Test void 기본값은_HIKER() {
        var setting = new ReelsSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        assertThat(setting.current()).isEqualTo(ReelsSource.HIKER);
    }

    @Test void 저장한_값을_읽는다() {
        var setting = new ReelsSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.update(ReelsSource.ACTOR);
        assertThat(setting.current()).isEqualTo(ReelsSource.ACTOR);
    }

    @Test void 이상한_값이면_HIKER로_폴백() {
        var setting = new ReelsSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        setting.updateRaw("GARBAGE");
        assertThat(setting.current()).isEqualTo(ReelsSource.HIKER);
    }
}
