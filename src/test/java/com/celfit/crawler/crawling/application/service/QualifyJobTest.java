package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.domain.*;
import com.celfit.crawler.content.domain.*;
import com.celfit.crawler.settings.domain.*;
import com.celfit.crawler.crawling.application.port.out.*;
import com.celfit.crawler.content.application.port.out.*;
import com.celfit.crawler.settings.application.port.out.*;
import com.celfit.crawler.settings.application.service.ProfileSourceSetting;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

@Import(QualifyJobTest.Config.class)
@Transactional
class QualifyJobTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }
    }

    @Autowired FakeApifyRunner fake;
    @Autowired QualifyJob job;
    @Autowired ProfileSourceSetting profileSourceSetting;

    // QualifyJob은 프로필 수집을 ProfileSourceSelector(Task 9)에 위임한다.
    // 기본 소스는 SELF(web_profile_info, 실 네트워크)이므로, 이 테스트가 기대하는
    // "FakeApifyRunner로 프로필 응답을 스텁"하는 기존 시나리오를 유지하려면
    // 소스를 ACTOR로 고정해 ActorProfileFetcher(→ CrawlExecutor→ApifyRunnerPort) 경로를 태운다.
    @org.junit.jupiter.api.BeforeEach
    void resetFake() {
        fake.reset();
        profileSourceSetting.update(ProfileSource.ACTOR);
    }

    @Autowired CategoryRepository categories;
    @Autowired CollectionRuleRepository rules;
    @Autowired ContentRepository contents;
    @Autowired AccountRepository accounts;
    @Autowired RawProfileRepository rawProfiles;

    Long catId;

    Content seedContent(String shortCode, String owner) {
        if (catId == null) catId = categories.save(new Category("메이크업")).getId();
        accounts.findByUsername(owner).orElseGet(() -> accounts.save(new Account(owner)));
        return contents.save(new Content(shortCode, ContentType.REELS, owner,
                Instant.parse("2026-07-01T00:00:00Z"), catId, "메이크업", Instant.now()));
    }

    void seedRule(Integer min, Integer max) {
        CollectionRule rule = new CollectionRule(catId);
        rule.setMinFollowers(min);
        rule.setMaxFollowers(max);
        rules.save(rule);
    }

    static Map<String, Object> profile(String username, int followers) {
        return Map.of("username", username, "followersCount", followers);
    }

    @Test
    void 프로필을_수집하고_팔로워_규칙으로_판정한다() {
        seedContent("sc1", "big");    // 팔로워 충분 → QUALIFIED
        seedContent("sc2", "small");  // 부족 → EXCLUDED
        seedRule(10_000, null);
        fake.enqueue(List.of(profile("big", 50_000), profile("small", 300)));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.profiled()).isEqualTo(2);
        assertThat(contents.findByShortCode("sc1").orElseThrow().getStatus()).isEqualTo(ContentStatus.QUALIFIED);
        assertThat(contents.findByShortCode("sc2").orElseThrow().getStatus()).isEqualTo(ContentStatus.EXCLUDED);
        assertThat(rawProfiles.count()).isEqualTo(2);
        assertThat(accounts.findByUsername("big").orElseThrow().getLastProfiledAt()).isNotNull();
    }

    @Test
    void 규칙이_없으면_전부_QUALIFIED_프로필은_그래도_수집() {
        seedContent("sc1", "kim");
        fake.enqueue(List.of(profile("kim", 5)));

        job.run(TriggerType.MANUAL);

        assertThat(contents.findByShortCode("sc1").orElseThrow().getStatus()).isEqualTo(ContentStatus.QUALIFIED);
    }

    @Test
    void 이미_프로필된_계정은_재수집하지_않는다() {
        Content c = seedContent("sc1", "kim");
        Account kim = accounts.findByUsername("kim").orElseThrow();
        kim.setLastProfiledAt(Instant.now());
        accounts.save(kim);
        // 프로필 액터 호출이 없어야 하므로 스크립트 안 넣음 — 호출되면 fake가 예외

        job.run(TriggerType.MANUAL);

        assertThat(fake.calls).isEmpty();
        assertThat(contents.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(ContentStatus.QUALIFIED);
    }

    @Test
    void 팔로워_규칙이_있는데_프로필_미확보면_PENDING_유지() {
        seedContent("sc1", "ghost");
        seedRule(1000, null);
        fake.enqueue(List.of());  // 프로필 응답에 ghost 없음 (비공개 등)

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.deferred()).isEqualTo(1);
        assertThat(contents.findByShortCode("sc1").orElseThrow().getStatus()).isEqualTo(ContentStatus.PENDING);
    }

    @Test
    void requalify는_EXCLUDED를_Apify_재호출_없이_재판정한다() {
        seedContent("sc1", "kim");
        seedRule(10_000, null);
        fake.enqueue(List.of(profile("kim", 500)));
        job.run(TriggerType.MANUAL);
        assertThat(contents.findByShortCode("sc1").orElseThrow().getStatus()).isEqualTo(ContentStatus.EXCLUDED);

        // 규칙 완화 후 재판정 — raw_profile 재사용, 액터 추가 호출 없음
        CollectionRule rule = rules.findByCategoryId(catId).orElseThrow();
        rule.setMinFollowers(100);
        rules.save(rule);

        job.run(TriggerType.MANUAL, true);

        assertThat(contents.findByShortCode("sc1").orElseThrow().getStatus()).isEqualTo(ContentStatus.QUALIFIED);
        assertThat(fake.calls).hasSize(1);  // 처음 1회뿐
    }
}
