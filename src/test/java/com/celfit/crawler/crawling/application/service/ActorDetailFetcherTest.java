package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.Actors;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActorDetailFetcherTest {

    ActorDetailFetcher f = new ActorDetailFetcher(null);

    @Test void 릴스는_reel액터_reelUrl() {
        assertThat(f.actorFor(ContentType.REELS)).isEqualTo(Actors.DETAIL_REELS);
        assertThat(f.urlsFor(List.of("ABC"), ContentType.REELS).get(0)).contains("/reel/ABC");
    }

    @Test void 피드는_post액터_postUrl() {
        assertThat(f.actorFor(ContentType.FEED)).isEqualTo(Actors.DETAIL_FEED);
        assertThat(f.urlsFor(List.of("ABC"), ContentType.FEED).get(0)).contains("/p/ABC");
    }

    @Test void 양타입_지원_source_ACTOR() {
        assertThat(f.supports(ContentType.REELS)).isTrue();
        assertThat(f.supports(ContentType.FEED)).isTrue();
        assertThat(f.source().name()).isEqualTo("ACTOR");
    }
}
