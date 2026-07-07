package com.celfit.crawler.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.domain.Category;
import com.celfit.crawler.domain.CategoryKeyword;
import com.celfit.crawler.domain.CategoryRepository;
import com.celfit.crawler.domain.CategoryKeywordRepository;
import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.job.JobLock;
import java.util.List;
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
    @Autowired JobLock lock;

    @org.junit.jupiter.api.BeforeEach
    void resetFake() {
        fake.reset();
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
    void discover에_category_없으면_400_모르는_잡도_400() throws Exception {
        mvc.perform(post("/admin/jobs/discover")).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/jobs/terraform")).andExpect(status().isBadRequest());
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
