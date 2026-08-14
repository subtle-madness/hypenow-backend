package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.ApifyRunnerPort;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.application.port.out.CrawlRunRepository;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.application.port.out.PaidCallCounter;
import com.celfit.crawler.crawling.domain.RawRunItem;
import com.celfit.crawler.crawling.application.port.out.RawRunItemRepository;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 액터 실행 1회를 crawl_run으로 감싼다: RUNNING 기록 → 실행 → SUCCEEDED/FAILED 마감.
 * crawl_run 저장은 REQUIRES_NEW가 아니라 호출자 트랜잭션에 합류한다 — 잡 단위 원자성 우선.
 * 성공 응답의 전 아이템은 raw_run_item으로 아카이브한다 — 이후 잡이 무엇을 버리든
 * (규칙 탈락 등) 과금된 응답이 남도록. 실패 경로는 아이템이 없으므로 아카이브도 없다.
 * 단, 응답 payload가 타입 raw 테이블에 1:1 무가공 저장되는 잡({@link JobName#archivesRunItems()}
 * false — COLLECT·REELS)은 사본을 남기지 않는다. 원형 보존처가 타입 테이블로 옮겨간 것뿐이라
 * "과금된 응답은 반드시 어딘가 남는다"는 보장은 유지된다.
 * 실행이 실패해도 그때까지 산 유료 요청 수는 request_count에 남긴다({@link PaidCallCounter}) —
 * 실패는 장애 구간에 몰려서, 유실을 두면 비용을 되묻는 바로 그 시점에 집계가 가장 크게 틀린다.
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
    private final PaidCallCounter paidCalls;
    private final Clock clock;

    public CrawlExecutor(ApifyRunnerPort runner, CrawlRunRepository runs,
                         RawRunItemRepository rawRunItems, PaidCallCounter paidCalls, Clock clock) {
        this.runner = runner;
        this.runs = runs;
        this.rawRunItems = rawRunItems;
        this.paidCalls = paidCalls;
        this.clock = clock;
    }

    public Execution execute(JobName job, TriggerType trigger, String keyword,
                             String targetUsername, String actorId, Map<String, Object> input) {
        return execute(job, trigger, keyword, targetUsername, actorId, () -> runner.run(actorId, input));
    }

    public Execution execute(JobName job, TriggerType trigger, String keyword,
                             String targetUsername, String actorId, Supplier<ApifyResult> work) {
        CrawlRun run = runs.save(new CrawlRun(job, trigger, keyword, targetUsername, actorId, clock.instant()));
        // 이 실행이 산 유료 콜의 실측치 — CountingHikerHttp가 성공 응답마다 채운다(PaidCallCounter).
        AtomicInteger paid = new AtomicInteger();
        try {
            ApifyResult result = paidCalls.scoped(paid, work);
            // 성공 경로는 소스가 스스로 보고한 값을 그대로 쓴다 — 실측치로 갈아끼우지 않는다.
            // 잡별 규칙(ReelsJob·SimilarJob이 soft-404를 '요청은 이미 샀다'며 1로 세는 것)을
            // 이 변경이 조용히 뒤집지 않게 하기 위함. 실측치를 쓰는 곳은 값이 아예 없던
            // 실패 경로뿐이라, 이 수정으로 성공 실행의 집계는 한 건도 달라지지 않는다.
            run.finishOk(result.runId(), result.requestCount(), result.items().size(), clock.instant());
            runs.save(run);
            if (job.archivesRunItems()) {
                archive(run.getId(), result.items());
            }
            return new Execution(run.getId(), result.items(), result.notFound());
        } catch (ApifyException e) {
            // 실패해도 이미 과금된 요청은 남긴다 — ApifyResult를 못 받는 경로라 실측 카운터가
            // 유일한 산지다. 0이면 null(산 게 없음) — 비용 뷰의 request_count > 0 모수와 정합.
            int bought = paid.get();
            run.finishFailed(e.getMessage(), bought > 0 ? bought : null, clock.instant());
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
