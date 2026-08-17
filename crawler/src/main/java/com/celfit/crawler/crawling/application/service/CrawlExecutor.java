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
            // 소스가 스스로 보고한 값이 이긴다 — 잡별 규칙(ReelsJob·SimilarJob이 soft-404를
            // '요청은 이미 샀다'며 1로 세는 것)을 실측치가 조용히 뒤집지 않게 하기 위함.
            // 보고가 없으면(null) 실측치로 채운다: 유료 프로필 페처 4종이 여기 해당한다.
            // 무료 소스(SELF·자체크롤)와 Apify는 유료 전송을 안 지나 실측이 0이라 null로 남는다.
            run.finishOk(result.runId(), boughtOrReported(result.requestCount(), paid),
                    result.items().size(), clock.instant());
            runs.save(run);
            if (job.archivesRunItems()) {
                archive(run.getId(), result.items());
            }
            return new Execution(run.getId(), result.items(), result.notFound());
        } catch (ApifyException e) {
            // 실패해도 이미 과금된 요청은 남긴다 — ApifyResult를 못 받는 경로라 실측 카운터가
            // 유일한 산지다.
            run.finishFailed(e.getMessage(), boughtOrReported(null, paid), clock.instant());
            runs.save(run);
            throw e;
        }
    }

    /**
     * 소스가 보고한 값이 있으면 그 값, 없으면 실측치. 실측이 0이면 null이다 — "과금 없음"은
     * Apify 실행·무료 소스가 이미 null로 쓰고, 비용 뷰의 모수도 {@code request_count > 0}이라
     * 0과 null이 어차피 같이 빠진다. 표기를 하나로 맞춰 둔다.
     */
    private static Integer boughtOrReported(Integer reported, AtomicInteger paid) {
        if (reported != null) {
            return reported;
        }
        int bought = paid.get();
        return bought > 0 ? bought : null;
    }

    private void archive(Long runId, List<Map<String, Object>> items) {
        List<RawRunItem> toSave = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            toSave.add(new RawRunItem(runId, i, items.get(i)));
        }
        rawRunItems.saveAll(toSave);
    }
}
