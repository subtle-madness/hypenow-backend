package com.celfit.crawler.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DetailSourceSettingTest {

    @Test void 기본값_릴스HIKER_피드SELF() {
        var s = new DetailSourceSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        assertThat(s.sourceFor(ContentType.REELS)).isEqualTo(DetailSource.HIKER);
        assertThat(s.sourceFor(ContentType.FEED)).isEqualTo(DetailSource.SELF);
    }

    @Test void update_후_타입별로_읽힌다() {
        Map<String, String> store = new HashMap<>();
        var s = new DetailSourceSetting(ProfileSourceSettingTest.fakeRepo(store));
        s.update(DetailSource.ACTOR, DetailSource.ACTOR);
        assertThat(s.sourceFor(ContentType.REELS)).isEqualTo(DetailSource.ACTOR);
        assertThat(s.sourceFor(ContentType.FEED)).isEqualTo(DetailSource.ACTOR);
    }

    @Test void 이상값이면_타입_기본값_폴백() {
        Map<String, String> store = new HashMap<>();
        store.put("detail.reels.source", "GARBAGE");
        var s = new DetailSourceSetting(ProfileSourceSettingTest.fakeRepo(store));
        assertThat(s.sourceFor(ContentType.REELS)).isEqualTo(DetailSource.HIKER);
    }
}
