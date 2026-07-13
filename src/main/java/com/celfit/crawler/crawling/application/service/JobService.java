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
    private final DiscoverJob discoverJob;
    private final QualifyJob qualifyJob;
    private final CollectJob collectJob;
    private final TaskExecutor taskExecutor;

    public JobService(JobLock lock, DiscoverJob discoverJob, QualifyJob qualifyJob, CollectJob collectJob,
                      @Qualifier("jobTaskExecutor") TaskExecutor taskExecutor) {
        this.lock = lock;
        this.discoverJob = discoverJob;
        this.qualifyJob = qualifyJob;
        this.collectJob = collectJob;
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
        taskExecutor.execute(() -> {
            try {
                switch (job) {
                    case DISCOVER -> log.info("discover 완료: {}", discoverJob.run(triggerType));
                    case QUALIFY -> log.info("qualify 완료: {}", qualifyJob.run(triggerType, requalify));
                    case COLLECT -> log.info("collect 완료: {}", collectJob.run(triggerType));
                }
            } catch (Exception e) {
                log.error("{} 잡 실패", job, e);
            } finally {
                lock.release(job);
            }
        });
        return TriggerResult.ACCEPTED;
    }
}
