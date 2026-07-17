package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.ApifyRunnerPort;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.application.port.out.CrawlRunRepository;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawRunItem;
import com.celfit.crawler.crawling.application.port.out.RawRunItemRepository;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 액터 실행 1회를 crawl_run으로 감싼다: RUNNING 기록 → 실행 → SUCCEEDED/FAILED 마감.
 * crawl_run 저장은 REQUIRES_NEW가 아니라 호출자 트랜잭션에 합류한다 — 잡 단위 원자성 우선.
 * 성공 응답의 전 아이템은 raw_run_item으로 무조건 아카이브한다 — 이후 잡이 무엇을 버리든
 * (규칙 탈락 등) 과금된 응답은 여기 남는다. 실패 경로는 아이템이 없으므로 아카이브도 없다.
 */
@Component
public class CrawlExecutor {

    /** notFound — 404로 판명된 대상 username(계정 소멸). 호출자가 소프트 딜리트한다. */
    public record Execution(Long runId, List<Map<String, Object>> items, List<String> notFound) {
        public Execution(Long runId, List<Map<String, Object>> items) {
            this(runId, items, List.of());
        }
    }

    private final ApifyRunnerPort runner;
    private final CrawlRunRepository runs;
    private final RawRunItemRepository rawRunItems;
    private final Clock clock;

    public CrawlExecutor(ApifyRunnerPort runner, CrawlRunRepository runs,
                         RawRunItemRepository rawRunItems, Clock clock) {
        this.runner = runner;
        this.runs = runs;
        this.rawRunItems = rawRunItems;
        this.clock = clock;
    }

    public Execution execute(JobName job, TriggerType trigger, String keyword,
                             String targetUsername, String actorId, Map<String, Object> input) {
        return execute(job, trigger, keyword, targetUsername, actorId, () -> runner.run(actorId, input));
    }

    public Execution execute(JobName job, TriggerType trigger, String keyword,
                             String targetUsername, String actorId, Supplier<ApifyResult> work) {
        CrawlRun run = runs.save(new CrawlRun(job, trigger, keyword, targetUsername, actorId, clock.instant()));
        try {
            ApifyResult result = work.get();
            run.finishOk(result.runId(), result.requestCount(), result.items().size(), clock.instant());
            runs.save(run);
            archive(run.getId(), result.items());
            return new Execution(run.getId(), result.items(), result.notFound());
        } catch (ApifyException e) {
            run.finishFailed(e.getMessage(), clock.instant());
            runs.save(run);
            throw e;
        }
    }

    private void archive(Long runId, List<Map<String, Object>> items) {
        List<RawRunItem> toSave = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            toSave.add(new RawRunItem(runId, i, items.get(i)));
        }
        rawRunItems.saveAll(toSave);
    }
}
