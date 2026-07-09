package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.domain.Category;
import com.celfit.crawler.content.application.port.out.CategoryRepository;
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
    @Override
    public TriggerResult trigger(JobName job, Long categoryId, TriggerType triggerType) {
        return trigger(job, categoryId, triggerType, false);
    }

    /** requalify=true는 qualify에서 EXCLUDED도 재판정 (raw_profile 재사용 — Apify 재호출 없음). */
    @Override
    public TriggerResult trigger(JobName job, Long categoryId, TriggerType triggerType, boolean requalify) {
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
                    case QUALIFY -> log.info("qualify 완료: {}", qualifyJob.run(triggerType, requalify));
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
