package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.CommentSourceSetting;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommentSourceSelectorTest {

    static CommentFetcher fetcherOf(CommentSource s) {
        return new CommentFetcher() {
            public CrawlExecutor.Execution fetch(List<String> c, int l, TriggerType t) { return null; }
            public CommentSource source() { return s; }
        };
    }

    @Test
    void 설정이_DIRECT면_DIRECT_구현체를_반환한다() {
        var actor = fetcherOf(CommentSource.ACTOR);
        var direct = fetcherOf(CommentSource.DIRECT);
        var setting = mock(CommentSourceSetting.class);
        when(setting.current()).thenReturn(CommentSource.DIRECT);

        var selector = new CommentSourceSelector(List.of(actor, direct), setting);

        assertThat(selector.current()).isSameAs(direct);
    }

    @Test
    void 해당_구현체가_없으면_ACTOR로_폴백한다() {
        var actor = fetcherOf(CommentSource.ACTOR);
        var setting = mock(CommentSourceSetting.class);
        when(setting.current()).thenReturn(CommentSource.DIRECT);

        var selector = new CommentSourceSelector(List.of(actor), setting);

        assertThat(selector.current()).isSameAs(actor);
    }
}
