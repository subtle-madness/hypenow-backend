package com.celfit.crawler.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class ProfileSupplementSettingTest {
    @Test void 기본값은_둘다_false() {
        var s = new ProfileSupplementSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        assertThat(s.postsEnabled()).isFalse();
        assertThat(s.relatedEnabled()).isFalse();
    }
    @Test void 개별_토글이_독립적으로_저장된다() {
        var s = new ProfileSupplementSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        s.update(true, false);
        assertThat(s.postsEnabled()).isTrue();
        assertThat(s.relatedEnabled()).isFalse();
    }
}
