package com.celfit.crawler.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.apify.ApifyException;
import com.celfit.crawler.domain.CrawlRunRepository;
import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.domain.RawRunItemRepository;
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
import org.springframework.transaction.annotation.Transactional;

@Import(CrawlExecutorTest.Config.class)
@Transactional  // raw_run_item 등 삽입이 롤백되도록 — 다른 테스트 클래스와 DB 공유(싱글턴 컨테이너)
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
    @Autowired RawRunItemRepository rawRunItems;

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
    void 성공하면_응답_아이템_전부가_raw_run_item으로_아카이브된다() {
        fake.enqueue(List.of(Map.of("shortCode", "a"), Map.of("shortCode", "b")));

        var execution = executor.execute(JobName.DISCOVER, TriggerType.MANUAL,
                null, "메이크업", "actor-x", Map.of("k", "v"));

        assertThat(rawRunItems.countByCrawlRunId(execution.runId())).isEqualTo(2);
        var byIndex = rawRunItems.findAll().stream()
                .filter(i -> i.getCrawlRunId().equals(execution.runId()))
                .sorted((a, b) -> Integer.compare(a.getItemIndex(), b.getItemIndex()))
                .toList();
        assertThat(byIndex.get(0).getPayload()).containsEntry("shortCode", "a");
        assertThat(byIndex.get(1).getPayload()).containsEntry("shortCode", "b");
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
        assertThat(rawRunItems.countByCrawlRunId(run.getId())).isZero();
    }
}
