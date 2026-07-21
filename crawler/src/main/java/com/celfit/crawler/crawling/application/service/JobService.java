package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.in.TriggerJobUseCase;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class JobService implements TriggerJobUseCase {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobLock lock;
    private final JobStopFlag stopFlag;
    private final DiscoverJob discoverJob;
    private final QualifyJob qualifyJob;
    private final CollectJob collectJob;
    private final BeautyJob beautyJob;
    private final SimilarJob similarJob;
    private final ReelsJob reelsJob;
    private final TaskExecutor taskExecutor;

    public JobService(JobLock lock, JobStopFlag stopFlag, DiscoverJob discoverJob, QualifyJob qualifyJob,
                      CollectJob collectJob, BeautyJob beautyJob, SimilarJob similarJob, ReelsJob reelsJob,
                      @Qualifier("jobTaskExecutor") TaskExecutor taskExecutor) {
        this.lock = lock;
        this.stopFlag = stopFlag;
        this.discoverJob = discoverJob;
        this.qualifyJob = qualifyJob;
        this.collectJob = collectJob;
        this.beautyJob = beautyJob;
        this.similarJob = similarJob;
        this.reelsJob = reelsJob;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public TriggerResult trigger(JobName job, TriggerType triggerType) {
        return trigger(job, triggerType, false);
    }

    /** requalify=true는 qualify에서 EXCLUDED도 재판정 (raw_profile 재사용 — Apify 재호출 없음). */
    @Override
    public TriggerResult trigger(JobName job, TriggerType triggerType, boolean requalify) {
        if (!lock.tryAcquire(job)) return TriggerResult.BUSY;
        stopFlag.clear(job);   // 직전 실행 말미의 늦은 중지 요청이 이번 실행을 죽이지 않게
        taskExecutor.execute(() -> {
            try {
                // 부분 실패(청크·키워드·방문 단위)는 잡을 멈추지 않지만 로그 레벨로 드러낸다 —
                // "완료" INFO와 실행 이력의 FAILED run이 모순처럼 보이던 문제 방지.
                switch (job) {
                    case DISCOVER -> {
                        var s = discoverJob.run(triggerType);
                        if (s.failedKeywords() > 0) log.warn("discover 완료(부분 실패): {}", s);
                        else log.info("discover 완료: {}", s);
                    }
                    case QUALIFY -> {
                        var s = qualifyJob.run(triggerType, requalify);
                        if (s.failedChunks() > 0) log.warn("qualify 완료(프로필 수집 부분 실패): {}", s);
                        else log.info("qualify 완료: {}", s);
                    }
                    case COLLECT -> {
                        var s = collectJob.run(triggerType);
                        if (s.failedVisits() > 0) log.warn("collect 완료(방문 부분 실패): {}", s);
                        else log.info("collect 완료: {}", s);
                    }
                    case BEAUTY -> {
                        // requalify 플래그를 뷰티 재판정(rejudge)으로 재사용 — MANUAL은 잡이 보존
                        var s = beautyJob.run(triggerType, requalify);
                        if (s.failedBatches() > 0) log.warn("beauty 완료(배치 부분 실패): {}", s);
                        else log.info("beauty 완료: {}", s);
                    }
                    case SIMILAR -> {
                        var s = similarJob.run(triggerType);
                        if (s.failedSeeds() > 0) log.warn("similar 완료(시드 부분 실패): {}", s);
                        else log.info("similar 완료: {}", s);
                    }
                    case REELS -> {
                        var s = reelsJob.run(triggerType);
                        if (s.failedVisits() > 0) log.warn("reels 완료(방문 부분 실패): {}", s);
                        else log.info("reels 완료: {}", s);
                    }
                    case RESNAPSHOT, AGGREGATE -> log.warn("{}은(는) 판독 전용 — 실행 경로 없음", job);
                }
            } catch (Exception e) {
                log.error("{} 잡 실패", job, e);
            } finally {
                lock.release(job);
            }
        });
        return TriggerResult.ACCEPTED;
    }

    @Override
    public StopResult stop(JobName job) {
        if (!lock.isRunning(job)) return StopResult.NOT_RUNNING;
        stopFlag.request(job);
        log.info("{} 중지 요청 — 진행 중인 단위 작업까지 마치고 조기 종료한다", job);
        return StopResult.STOP_REQUESTED;
    }
}
