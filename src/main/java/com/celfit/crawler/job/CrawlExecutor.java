package com.celfit.crawler.job;

import com.celfit.crawler.apify.ApifyException;
import com.celfit.crawler.apify.ApifyResult;
import com.celfit.crawler.apify.ApifyRunner;
import com.celfit.crawler.domain.CrawlRun;
import com.celfit.crawler.domain.CrawlRunRepository;
import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.domain.TriggerType;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 액터 실행 1회를 crawl_run으로 감싼다: RUNNING 기록 → 실행 → SUCCEEDED/FAILED 마감.
 * crawl_run 저장은 REQUIRES_NEW가 아니라 호출자 트랜잭션에 합류한다 — 잡 단위 원자성 우선.
 */
@Component
public class CrawlExecutor {

    public record Execution(Long runId, List<Map<String, Object>> items) {}

    private final ApifyRunner runner;
    private final CrawlRunRepository runs;
    private final Clock clock;

    public CrawlExecutor(ApifyRunner runner, CrawlRunRepository runs, Clock clock) {
        this.runner = runner;
        this.runs = runs;
        this.clock = clock;
    }

    public Execution execute(JobName job, TriggerType trigger, Long categoryId,
                             String keyword, String actorId, Map<String, Object> input) {
        CrawlRun run = runs.save(new CrawlRun(job, trigger, categoryId, keyword, actorId, clock.instant()));
        try {
            ApifyResult result = runner.run(actorId, input);
            run.finishOk(result.runId(), result.items().size(), clock.instant());
            runs.save(run);
            return new Execution(run.getId(), result.items());
        } catch (ApifyException e) {
            run.finishFailed(e.getMessage(), clock.instant());
            runs.save(run);
            throw e;
        }
    }
}
