package com.celfit.crawler.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RawEntityTest extends IntegrationTest {

    @Autowired RawCommentRepository rawComments;
    @Autowired RawProfileRepository rawProfiles;
    @Autowired ContentRepository contents;
    @Autowired AccountRepository accounts;
    @Autowired CategoryRepository categories;
    @Autowired CrawlRunRepository runs;
    @Autowired EntityManager em;

    @Test
    void payload가_jsonb로_왕복되고_generated_column이_읽힌다() {
        Long catId = categories.save(new Category("메이크업")).getId();
        Content content = contents.save(new Content("sc1", ContentType.REELS, "kim",
                Instant.parse("2026-07-01T00:00:00Z"), catId, "메이크업", Instant.now()));
        CrawlRun run = runs.save(new CrawlRun(JobName.AGGREGATE, TriggerType.MANUAL,
                null, null, "actor", Instant.now()));

        Map<String, Object> payload = Map.of(
                "ownerUsername", "kim",
                "text", "너무 예뻐요",
                "timestamp", "2026-07-02T10:00:00.000Z",
                "likesCount", 7,
                "replies", List.of(Map.of("text", "감사합니다")));
        RawComment saved = rawComments.save(
                new RawComment(content.getId(), run.getId(), payload, Instant.now()));
        em.flush();
        em.refresh(saved);  // DB가 계산한 generated column 읽기

        assertThat(saved.getPayload().get("text")).isEqualTo("너무 예뻐요");
        assertThat(saved.getPayload().get("replies")).isEqualTo(List.of(Map.of("text", "감사합니다")));
        assertThat(saved.getWriter()).isEqualTo("kim");
        assertThat(saved.getText()).isEqualTo("너무 예뻐요");
    }

    @Test
    void raw_profile의_followers_generated_column() {
        Account acct = accounts.save(new Account("kim"));
        CrawlRun run = runs.save(new CrawlRun(JobName.QUALIFY, TriggerType.MANUAL,
                null, null, "actor", Instant.now()));
        RawProfile saved = rawProfiles.save(new RawProfile(acct.getId(), run.getId(),
                Map.of("username", "kim", "followersCount", 123456), Instant.now()));
        em.flush();
        em.refresh(saved);

        assertThat(saved.getFollowers()).isEqualTo(123456L);
    }
}
