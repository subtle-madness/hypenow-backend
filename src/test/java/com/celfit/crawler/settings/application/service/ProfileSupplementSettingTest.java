package com.celfit.crawler.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class ProfileSupplementSettingTest {
    @Test void 기본값은_false() {
        var s = new ProfileSupplementSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        assertThat(s.relatedEnabled()).isFalse();
    }
    @Test void 토글값이_저장된다() {
        var s = new ProfileSupplementSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        s.update(true);
        assertThat(s.relatedEnabled()).isTrue();
    }
}
