package com.celfit.crawler.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.domain.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

@Import(DiscoverJobTest.Config.class)
@Transactional
class DiscoverJobTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }
    }

    @Autowired FakeApifyRunner fake;
    @Autowired DiscoverJob job;

    @org.junit.jupiter.api.BeforeEach
    void resetFake() {
        fake.reset();
    }

    @Autowired CategoryRepository categories;
    @Autowired CategoryKeywordRepository keywords;
    @Autowired CollectionRuleRepository rules;
    @Autowired ContentRepository contents;
    @Autowired AccountRepository accounts;
    @Autowired RawDiscoveryPostRepository rawDiscovery;
    @Autowired RawRunItemRepository rawRunItems;

    static Map<String, Object> item(String shortCode, String productType, String owner) {
        return productType == null
                ? Map.of("shortCode", shortCode, "timestamp", "2026-07-01T12:00:00.000Z", "ownerUsername", owner)
                : Map.of("shortCode", shortCode, "productType", productType,
                         "timestamp", "2026-07-01T12:00:00.000Z", "ownerUsername", owner);
    }

    Long seedCategory(String... kws) {
        Long catId = categories.save(new Category("메이크업")).getId();
        for (String kw : kws) keywords.save(new CategoryKeyword(catId, kw));
        return catId;
    }

    @Test
    void 발굴_아이템이_content와_raw로_등록된다() {
        Long catId = seedCategory("메이크업");
        fake.enqueue(List.of(item("sc1", "clips", "kim"), item("sc2", null, "lee")));

        var summary = job.run(catId, TriggerType.MANUAL);

        assertThat(summary.newContents()).isEqualTo(2);
        Content c1 = contents.findByShortCode("sc1").orElseThrow();
        assertThat(c1.getContentType()).isEqualTo(ContentType.REELS);
        assertThat(c1.getStatus()).isEqualTo(ContentStatus.PENDING);
        assertThat(c1.getDiscoveryKeyword()).isEqualTo("메이크업");
        assertThat(accounts.findByUsername("kim")).isPresent();
        assertThat(rawDiscovery.count()).isEqualTo(2);
        // 한글 키워드 → keywordSearch 자동 전환 확인
        assertThat(fake.calls.get(0).input()).containsEntry("keywordSearch", true);
    }

    @Test
    void 재발굴은_content를_안_늘리고_raw_이력만_쌓는다() {
        Long catId = seedCategory("메이크업");
        fake.enqueue(List.of(item("sc1", "clips", "kim")));
        fake.enqueue(List.of(item("sc1", "clips", "kim")));

        job.run(catId, TriggerType.MANUAL);
        var second = job.run(catId, TriggerType.MANUAL);

        assertThat(second.newContents()).isZero();
        assertThat(second.duplicates()).isEqualTo(1);
        assertThat(contents.count()).isEqualTo(1);
        assertThat(rawDiscovery.count()).isEqualTo(2);
    }

    @Test
    void content_types_규칙에_안_맞으면_완전히_skip() {
        Long catId = seedCategory("메이크업");
        CollectionRule rule = new CollectionRule(catId);
        rule.setContentTypes(ContentTypeFilter.REELS);
        rules.save(rule);
        fake.enqueue(List.of(item("reel1", "clips", "kim"), item("feed1", null, "lee")));

        var summary = job.run(catId, TriggerType.MANUAL);

        assertThat(summary.newContents()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(contents.findByShortCode("feed1")).isEmpty();
        assertThat(rawDiscovery.count()).isEqualTo(1);
        // 파이프라인이 버린 아이템(content_types 불일치)도 응답 아카이브에는 남는다 — 과금한 응답 전량 보관
        assertThat(rawRunItems.count()).isEqualTo(2);
    }

    @Test
    void 한_키워드가_실패해도_다음_키워드는_진행된다() {
        Long catId = seedCategory("메이크업", "화장품추천");
        fake.enqueueFailure("액터 폭발");
        fake.enqueue(List.of(item("sc9", "clips", "park")));

        var summary = job.run(catId, TriggerType.MANUAL);

        assertThat(summary.failedKeywords()).isEqualTo(1);
        assertThat(summary.newContents()).isEqualTo(1);
    }
}
