package com.celfit.crawler.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.apify.ApifyException;
import com.celfit.crawler.domain.CrawlRunRepository;
import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.domain.RunStatus;
import com.celfit.crawler.domain.TriggerType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@Import(CrawlExecutorTest.Config.class)
class CrawlExecutorTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }
    }

    @Autowired FakeApifyRunner fake;
    @Autowired CrawlExecutor executor;
    @Autowired CrawlRunRepository runs;

    @Test
    void 성공하면_crawl_run이_SUCCEEDED로_기록된다() {
        fake.enqueue(List.of(Map.of("shortCode", "a"), Map.of("shortCode", "b")));

        var execution = executor.execute(JobName.DISCOVER, TriggerType.MANUAL,
                null, "메이크업", "actor-x", Map.of("k", "v"));

        assertThat(execution.items()).hasSize(2);
        var run = runs.findById(execution.runId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getItemCount()).isEqualTo(2);
        assertThat(run.getApifyRunId()).isEqualTo("fake-run-1");
        assertThat(run.getKeyword()).isEqualTo("메이크업");
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void 실패하면_FAILED로_기록되고_예외가_전파된다() {
        fake.enqueueFailure("보이지 않는 손");

        assertThatThrownBy(() -> executor.execute(JobName.QUALIFY, TriggerType.MANUAL,
                null, null, "actor-x", Map.of()))
                .isInstanceOf(ApifyException.class);

        var run = runs.findTop50ByOrderByIdDesc().get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("보이지 않는 손");
    }
}
