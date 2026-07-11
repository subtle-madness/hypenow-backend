package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.DetailFetcher;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.DetailSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSourceSettingTest;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class DetailSourceSelectorTest {

    /** source/supports만 다른 스텁 fetcher. */
    static DetailFetcher stub(DetailSource src, ContentType supported) {
        return new DetailFetcher() {
            @Override public CrawlExecutor.Execution fetch(List<String> s, ContentType t, TriggerType tr) { return null; }
            @Override public DetailSource source() { return src; }
            @Override public boolean supports(ContentType t) { return t == supported || src == DetailSource.ACTOR; }
        };
    }

    DetailSourceSetting settingWith(String reels, String feed) {
        var store = new HashMap<String, String>();
        if (reels != null) store.put("detail.reels.source", reels);
        if (feed != null) store.put("detail.feed.source", feed);
        return new DetailSourceSetting(ProfileSourceSettingTest.fakeRepo(store));
    }

    @Test void 기본_릴스는_HIKER_피드는_SELF_선택() {
        var hiker = stub(DetailSource.HIKER, ContentType.REELS);
        var self = stub(DetailSource.SELF, ContentType.FEED);
        var actor = stub(DetailSource.ACTOR, ContentType.REELS);
        var sel = new DetailSourceSelector(List.of(hiker, self, actor), settingWith(null, null));
        assertThat(sel.forType(ContentType.REELS)).isSameAs(hiker);
        assertThat(sel.forType(ContentType.FEED)).isSameAs(self);
    }

    @Test void 설정이_ACTOR면_ACTOR_선택() {
        var hiker = stub(DetailSource.HIKER, ContentType.REELS);
        var self = stub(DetailSource.SELF, ContentType.FEED);
        var actor = stub(DetailSource.ACTOR, ContentType.REELS);
        var sel = new DetailSourceSelector(List.of(hiker, self, actor), settingWith("ACTOR", "ACTOR"));
        assertThat(sel.forType(ContentType.REELS)).isSameAs(actor);
        assertThat(sel.forType(ContentType.FEED)).isSameAs(actor);
    }
}
