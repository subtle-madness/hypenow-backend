package com.celfit.crawler.crawling.domain;

import com.celfit.crawler.crawling.application.port.out.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RawEntityTest extends IntegrationTest {

    @Autowired
    RawCommentRepository rawComments;
    @Autowired
    RawProfileRepository rawProfiles;
    @Autowired
    RawMediaPageRepository rawMediaPages;
    @Autowired
    ContentRepository contents;
    @Autowired
    InfluencerRepository influencers;
    @Autowired
    CrawlRunRepository runs;
    @Autowired EntityManager em;

    @Test
    void payload가_jsonb로_왕복되고_source가_저장된다() {
        Influencer influencer = influencers.save(new Influencer("kim"));
        Content content = contents.save(new Content("sc1", ContentType.REELS, "kim",
                influencer.getId(), Instant.parse("2026-07-01T00:00:00Z"), Instant.now()));
        CrawlRun run = runs.save(new CrawlRun(JobName.COLLECT, TriggerType.MANUAL,
                null, null, "actor", Instant.now()));

        Map<String, Object> payload = Map.of(
                "ownerUsername", "kim",
                "text", "너무 예뻐요",
                "timestamp", "2026-07-02T10:00:00.000Z",
                "likesCount", 7,
                "replies", List.of(Map.of("text", "감사합니다")));
        RawComment saved = rawComments.save(
                new RawComment(content.getId(), run.getId(), RawSource.APIFY_ACTOR, payload, Instant.now()));
        em.flush();
        em.refresh(saved);

        assertThat(saved.getPayload().get("text")).isEqualTo("너무 예뻐요");
        assertThat(saved.getPayload().get("replies")).isEqualTo(List.of(Map.of("text", "감사합니다")));
        assertThat(saved.getSource()).isEqualTo(RawSource.APIFY_ACTOR);
        // 실컬럼은 DB generated가 아니라 애플리케이션(Task 4 추출기)이 세터로 채운다 — 이 태스크에서는 비어 있다
        assertThat(saved.getWriter()).isNull();
    }

    @Test
    void raw_profile은_influencer_id로_저장되고_source가_필수다() {
        Influencer influencer = influencers.save(new Influencer("kim"));
        CrawlRun run = runs.save(new CrawlRun(JobName.QUALIFY, TriggerType.MANUAL,
                null, null, "actor", Instant.now()));
        RawProfile saved = rawProfiles.save(new RawProfile(influencer.getId(), run.getId(),
                RawSource.HIKER_MOBILE, Map.of("username", "kim", "followersCount", 123456), Instant.now()));
        em.flush();
        em.refresh(saved);

        assertThat(saved.getInfluencerId()).isEqualTo(influencer.getId());
        assertThat(saved.getSource()).isEqualTo(RawSource.HIKER_MOBILE);
        // 실컬럼(followers)은 Task 4의 추출기가 세터로 채우기 전까지는 비어 있다
        assertThat(saved.getFollowers()).isNull();

        saved.setFollowers(123456L);
        rawProfiles.save(saved);
        em.flush();
        em.refresh(saved);
        assertThat(saved.getFollowers()).isEqualTo(123456L);
    }

    @Test
    void raw_media_page가_저장되고_왕복된다() {
        Influencer influencer = influencers.save(new Influencer("lee"));
        CrawlRun run = runs.save(new CrawlRun(JobName.DISCOVER, TriggerType.MANUAL,
                "키워드", null, "actor", Instant.now()));

        Map<String, Object> payload = Map.of("items", List.of(Map.of("shortCode", "sc1")));
        RawMediaPage saved = rawMediaPages.save(new RawMediaPage(influencer.getId(), run.getId(),
                RawSource.HIKER_GQL_MEDIAS, payload, Instant.now()));
        em.flush();
        em.refresh(saved);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getInfluencerId()).isEqualTo(influencer.getId());
        assertThat(saved.getCrawlRunId()).isEqualTo(run.getId());
        assertThat(saved.getSource()).isEqualTo(RawSource.HIKER_GQL_MEDIAS);
        assertThat(saved.getPayload().get("items")).isEqualTo(List.of(Map.of("shortCode", "sc1")));
    }
}
