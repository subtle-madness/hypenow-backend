package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.CommentSourceSetting;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommentSourceSelectorTest {

    static CommentFetcher fetcherOf(CommentSource s, RawSource rawSrc) {
        return new CommentFetcher() {
            public CommentResult fetch(List<String> c, int l, TriggerType t) { return null; }
            public CommentSource source() { return s; }
            public RawSource rawSource() { return rawSrc; }
        };
    }

    @Test
    void 설정이_DIRECT면_DIRECT_구현체를_반환한다() {
        var actor = fetcherOf(CommentSource.ACTOR, RawSource.APIFY_ACTOR);
        var direct = fetcherOf(CommentSource.DIRECT, RawSource.SELF_GQL);
        var setting = mock(CommentSourceSetting.class);
        when(setting.current()).thenReturn(CommentSource.DIRECT);

        var selector = new CommentSourceSelector(List.of(actor, direct), setting);

        assertThat(selector.current()).isSameAs(direct);
    }

    @Test
    void 해당_구현체가_없으면_ACTOR로_폴백한다() {
        var actor = fetcherOf(CommentSource.ACTOR, RawSource.APIFY_ACTOR);
        var setting = mock(CommentSourceSetting.class);
        when(setting.current()).thenReturn(CommentSource.DIRECT);

        var selector = new CommentSourceSelector(List.of(actor), setting);

        assertThat(selector.current()).isSameAs(actor);
    }

    @Test
    void currentSource가_설정된_소스의_RawSource를_반환한다() {
        var actor = fetcherOf(CommentSource.ACTOR, RawSource.APIFY_ACTOR);
        var direct = fetcherOf(CommentSource.DIRECT, RawSource.SELF_GQL);
        var setting = mock(CommentSourceSetting.class);
        when(setting.current()).thenReturn(CommentSource.DIRECT);

        var selector = new CommentSourceSelector(List.of(actor, direct), setting);

        assertThat(selector.currentSource()).isEqualTo(RawSource.SELF_GQL);
    }

    @Test
    void currentSource도_미등록_소스면_ACTOR로_폴백한다() {
        var actor = fetcherOf(CommentSource.ACTOR, RawSource.APIFY_ACTOR);
        var setting = mock(CommentSourceSetting.class);
        when(setting.current()).thenReturn(CommentSource.DIRECT);

        var selector = new CommentSourceSelector(List.of(actor), setting);

        assertThat(selector.currentSource()).isEqualTo(RawSource.APIFY_ACTOR);
    }
}
