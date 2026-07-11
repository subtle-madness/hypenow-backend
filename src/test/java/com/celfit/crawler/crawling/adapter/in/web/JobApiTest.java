package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.domain.Account;
import com.celfit.crawler.crawling.application.port.out.AccountRepository;
import com.celfit.crawler.content.domain.Category;
import com.celfit.crawler.content.domain.CategoryKeyword;
import com.celfit.crawler.content.application.port.out.CategoryRepository;
import com.celfit.crawler.content.application.port.out.CategoryKeywordRepository;
import com.celfit.crawler.content.domain.CollectionRule;
import com.celfit.crawler.content.application.port.out.CollectionRuleRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.ContentStatus;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.application.port.out.CrawlRunRepository;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.crawling.application.service.JobLock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Import(JobApiTest.Config.class)
@Transactional  // SyncTaskExecutor라 잡이 같은 스레드에서 돌아 테스트 tx에 합류 → 롤백으로 DB 오염 방지
class JobApiTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }

        @Bean("jobTaskExecutor") @Primary
        TaskExecutor syncJobExecutor() {
            return new SyncTaskExecutor();  // 트리거를 동기 실행으로 만들어 테스트 결정적
        }
    }

    @Autowired MockMvc mvc;
    @Autowired FakeApifyRunner fake;
    @Autowired CategoryRepository categories;
    @Autowired CategoryKeywordRepository keywords;
    @Autowired CollectionRuleRepository rules;
    @Autowired AccountRepository accounts;
    @Autowired ContentRepository contents;
    @Autowired CrawlRunRepository runs;
    @Autowired RawProfileRepository rawProfiles;
    @Autowired JobLock lock;
    @Autowired com.celfit.crawler.settings.application.service.DiscoverSourceSetting discoverSourceSetting;

    @org.junit.jupiter.api.BeforeEach
    void resetFake() {
        fake.reset();
        discoverSourceSetting.update(com.celfit.crawler.settings.domain.DiscoverSource.ACTOR);
    }

    @AfterEach
    void unlock() {
        for (JobName j : JobName.values()) lock.release(j);
    }

    @Test
    void discover_트리거는_202이고_잡이_실행된다() throws Exception {
        Long catId = categories.save(new Category("메이크업")).getId();
        keywords.save(new CategoryKeyword(catId, "메이크업"));
        fake.enqueue(List.of());

        mvc.perform(post("/admin/jobs/discover").param("category", String.valueOf(catId)))
                .andExpect(status().isAccepted());

        assertThat(fake.calls).hasSize(1);
    }

    @Test
    void 실행_중인_잡은_409() throws Exception {
        lock.tryAcquire(JobName.QUALIFY);
        mvc.perform(post("/admin/jobs/qualify")).andExpect(status().isConflict());
    }

    @Test
    void 모르는_잡은_400() throws Exception {
        mvc.perform(post("/admin/jobs/terraform")).andExpect(status().isBadRequest());
    }

    @Test
    void 카테고리_없는_discover는_전체_활성_카테고리를_순차_실행한다() throws Exception {
        Long cat1 = categories.save(new Category("메이크업")).getId();
        keywords.save(new CategoryKeyword(cat1, "메이크업"));
        Long cat2 = categories.save(new Category("스킨케어")).getId();
        keywords.save(new CategoryKeyword(cat2, "스킨케어"));
        Category disabled = new Category("비활성");
        disabled.setEnabled(false);
        Long cat3 = categories.save(disabled).getId();
        keywords.save(new CategoryKeyword(cat3, "비활성키워드"));
        fake.enqueue(List.of());
        fake.enqueue(List.of());

        mvc.perform(post("/admin/jobs/discover")).andExpect(status().isAccepted());

        assertThat(fake.calls).hasSize(2);  // 활성 2개만 — 비활성 카테고리는 호출 안 됨
    }

    @Test
    void requalify_트리거는_EXCLUDED를_Apify_재호출_없이_재판정한다() throws Exception {
        // EXCLUDED 상태 시드: 규칙 min 10000, 계정은 이미 프로필됨(followersCount 500)
        Long catId = categories.save(new Category("메이크업")).getId();
        CollectionRule rule = new CollectionRule(catId);
        rule.setMinFollowers(10_000);
        rules.save(rule);
        Account kim = accounts.save(new Account("kim"));
        kim.setLastProfiledAt(Instant.now());
        Long runId = runs.save(new CrawlRun(JobName.QUALIFY, TriggerType.MANUAL, null, null,
                "fake-actor", Instant.now())).getId();
        rawProfiles.save(new RawProfile(kim.getId(), runId,
                Map.of("username", "kim", "followersCount", 500), Instant.now()));
        Content c = contents.save(new Content("sc-req", ContentType.REELS, "kim",
                Instant.parse("2026-07-01T00:00:00Z"), catId, "메이크업", Instant.now()));
        c.setStatus(ContentStatus.EXCLUDED);
        c.setQualifiedAt(Instant.now());

        // 규칙 완화 후 requalify 트리거 — raw_profile 재사용으로 QUALIFIED 전환
        rule.setMinFollowers(100);

        mvc.perform(post("/admin/jobs/qualify").param("requalify", "true"))
                .andExpect(status().isAccepted());

        assertThat(contents.findByShortCode("sc-req").orElseThrow().getStatus())
                .isEqualTo(ContentStatus.QUALIFIED);
        assertThat(fake.calls).isEmpty();  // Apify 재호출 없음
    }

    @Test
    void runs와_status_조회() throws Exception {
        mvc.perform(get("/admin/runs")).andExpect(status().isOk());
        mvc.perform(get("/admin/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentByStatus.PENDING").exists())
                .andExpect(jsonPath("$.dueForAggregate").exists());
    }
}
