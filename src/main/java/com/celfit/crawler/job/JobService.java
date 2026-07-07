package com.celfit.crawler.job;

import com.celfit.crawler.domain.Category;
import com.celfit.crawler.domain.CategoryRepository;
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
    private final CategoryRepository categories;
    private final TaskExecutor taskExecutor;

    public JobService(JobLock lock, DiscoverJob discoverJob, QualifyJob qualifyJob,
                      AggregateJob aggregateJob, CategoryRepository categories,
                      @Qualifier("jobTaskExecutor") TaskExecutor taskExecutor) {
        this.lock = lock;
        this.discoverJob = discoverJob;
        this.qualifyJob = qualifyJob;
        this.aggregateJob = aggregateJob;
        this.categories = categories;
        this.taskExecutor = taskExecutor;
    }

    /** discover의 categoryId=null은 전체 활성 카테고리를 잡 1회(락 1회 점유) 안에서 순차 실행. */
    public TriggerResult trigger(JobName job, Long categoryId, TriggerType triggerType) {
        if (!lock.tryAcquire(job)) return TriggerResult.BUSY;
        taskExecutor.execute(() -> {
            try {
                switch (job) {
                    case DISCOVER -> {
                        if (categoryId != null) {
                            log.info("discover 완료: {}", discoverJob.run(categoryId, triggerType));
                        } else {
                            for (Category c : categories.findByEnabledTrue()) {
                                try {
                                    log.info("discover 완료 (카테고리={}): {}",
                                            c.getName(), discoverJob.run(c.getId(), triggerType));
                                } catch (Exception e) {
                                    // 한 카테고리 실패해도 다음 카테고리 계속 (run별 @Transactional로 격리)
                                    log.error("discover 실패 (카테고리={})", c.getName(), e);
                                }
                            }
                        }
                    }
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
