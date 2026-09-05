package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.CrawlRunRepository;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * "작업 단위 = 트랜잭션 1개"로 감싼 잡(CollectJob.visitOne 등)이 그 트랜잭션 안에서 실패하면,
 * CrawlExecutor.execute()가 같은 트랜잭션에 합류시켜 남긴 crawl_run 행(RUNNING/SUCCEEDED/FAILED
 * 무엇이든)까지 통째로 롤백된다(CrawlExecutor 클래스 주석 참고) — 그 결과 "무슨 일이 있었는지"가
 * 어디에도 남지 않는다(2026-09 IG 로그아웃 프로필 401 차단 사고 — crawl_run 마지막 행이 롤백 이전
 * 시점에 멈춰 있어 실패가 관측되지 않았다).
 *
 * <p>이 컴포넌트는 그 롤백 밖에서 "이 작업 단위는 실패했다"는 대표 행 1건을 REQUIRES_NEW
 * 트랜잭션으로 영속화한다. 어느 하위 단계(프로필 갱신·userId 추출 등)에서 터졌든 특정
 * 다운스트림 액터에 결부되지 않으므로 actor_id는 고정값 {@link #VISIT_ACTOR_LABEL}을 쓴다 —
 * 작업 단위당 이 행이 최대 1건만 생기게 호출자(visitOne의 바깥 catch)가 보장해야 한다.
 */
@Component
public class CrawlRunFailureRecorder {

    /** 특정 다운스트림 액터(actorId)를 가리키지 않는, 작업 단위 자체의 실패를 나타내는 고정 라벨. */
    static final String VISIT_ACTOR_LABEL = "visit";

    private final CrawlRunRepository runs;
    private final Clock clock;

    public CrawlRunFailureRecorder(CrawlRunRepository runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    /**
     * 새 트랜잭션(REQUIRES_NEW)에 crawl_run FAILED 행을 커밋한다 — 호출자의 작업 단위
     * 트랜잭션이 이미 롤백됐거나(일반적 경로) 아직 살아있어도(방어적) 이 행은 별도로 확정된다.
     *
     * @param errorMessage 어떤 계정이 왜 실패했는지 이 행만 보고 알 수 있어야 하므로
     *                     호출자가 targetUsername을 포함해 구성한다(로그 포맷과 동일 관례).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailed(JobName job, TriggerType trigger, String targetUsername,
                             Instant startedAt, String errorMessage) {
        CrawlRun run = new CrawlRun(job, trigger, null, targetUsername, VISIT_ACTOR_LABEL, startedAt);
        run.finishFailed(errorMessage, null, clock.instant());
        runs.save(run);
    }
}
