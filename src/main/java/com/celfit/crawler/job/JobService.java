package com.celfit.crawler.job;

import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.domain.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class JobService {

    public enum TriggerResult { ACCEPTED, BUSY }

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobLock lock;
    private final DiscoverJob discoverJob;
    private final QualifyJob qualifyJob;
    private final AggregateJob aggregateJob;
    private final TaskExecutor taskExecutor;

    public JobService(JobLock lock, DiscoverJob discoverJob, QualifyJob qualifyJob,
                      AggregateJob aggregateJob,
                      @Qualifier("jobTaskExecutor") TaskExecutor taskExecutor) {
        this.lock = lock;
        this.discoverJob = discoverJob;
        this.qualifyJob = qualifyJob;
        this.aggregateJob = aggregateJob;
        this.taskExecutor = taskExecutor;
    }

    public TriggerResult trigger(JobName job, Long categoryId, TriggerType triggerType) {
        if (job == JobName.DISCOVER && categoryId == null) {
            throw new IllegalArgumentException("discover는 category 파라미터가 필요합니다");
        }
        if (!lock.tryAcquire(job)) return TriggerResult.BUSY;
        taskExecutor.execute(() -> {
            try {
                switch (job) {
                    case DISCOVER -> log.info("discover 완료: {}", discoverJob.run(categoryId, triggerType));
                    case QUALIFY -> log.info("qualify 완료: {}", qualifyJob.run(triggerType));
                    case AGGREGATE -> log.info("aggregate 완료: {}", aggregateJob.run(triggerType));
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
