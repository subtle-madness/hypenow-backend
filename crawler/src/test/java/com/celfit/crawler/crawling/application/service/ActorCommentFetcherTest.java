package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

@Import(ActorCommentFetcherTest.Config.class)
@Transactional
class ActorCommentFetcherTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary FakeApifyRunner fakeApifyRunner() { return new FakeApifyRunner(); }
    }

    @Autowired FakeApifyRunner fake;
    @Autowired ActorCommentFetcher fetcher;

    @BeforeEach void reset() { fake.reset(); }

    @Test
    void source는_ACTOR다() {
        assertThat(fetcher.source()).isEqualTo(CommentSource.ACTOR);
    }

    @Test
    void rawSource는_APIFY_ACTOR다() {
        assertThat(fetcher.rawSource()).isEqualTo(RawSource.APIFY_ACTOR);
    }

    @Test
    void 댓글_액터를_postUrl_리스트로_호출하고_결과를_postUrl로_그룹핑한다() {
        fake.enqueue(List.of(Map.of("postUrl", "https://www.instagram.com/p/AA/",
                "ownerUsername", "fan", "text", "좋아요", "timestamp", "2026-01-01T00:00:00.000Z")));

        var result = fetcher.fetch(List.of("AA", "BB"), 50, TriggerType.MANUAL);

        assertThat(result.pagesByCode()).hasSize(1);
        assertThat(result.pagesByCode().get("AA")).hasSize(1);
        assertThat(result.pagesByCode().get("AA").get(0)).containsEntry("ownerUsername", "fan");
        assertThat(result.pagesByCode()).doesNotContainKey("BB"); // 응답에 없던 포스트

        var call = fake.calls.get(0);
        assertThat(call.actorId()).isEqualTo(Actors.COMMENT);
        assertThat(call.input()).containsEntry("resultsLimit", 50);
        assertThat(call.input().get("directUrls").toString()).contains("/p/AA/").contains("/p/BB/");
    }

    @Test
    void 같은_포스트의_댓글_아이템_여러개는_한_리스트로_모인다() {
        fake.enqueue(List.of(
                Map.of("postUrl", "https://www.instagram.com/p/AA/", "text", "c1"),
                Map.of("postUrl", "https://www.instagram.com/p/AA/", "text", "c2")));

        var result = fetcher.fetch(List.of("AA"), 50, TriggerType.MANUAL);

        assertThat(result.pagesByCode().get("AA")).hasSize(2);
    }
}
