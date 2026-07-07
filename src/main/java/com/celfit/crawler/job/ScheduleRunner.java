package com.celfit.crawler.job;

import com.celfit.crawler.domain.Category;
import com.celfit.crawler.domain.CategoryRepository;
import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.domain.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 스케줄 트리거 — crawler.schedule.enabled=true일 때만 활성. 초기 운영은 수동 트리거. */
@Component
@ConditionalOnProperty(prefix = "crawler.schedule", name = "enabled", havingValue = "true")
public class ScheduleRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRunner.class);

    private final JobService jobService;
    private final CategoryRepository categories;

    public ScheduleRunner(JobService jobService, CategoryRepository categories) {
        this.jobService = jobService;
        this.categories = categories;
    }

    @Scheduled(cron = "${crawler.schedule.discover-cron}")
    void discover() {
        for (Category c : categories.findByEnabledTrue()) {
            log.info("스케줄 discover 카테고리={}: {}", c.getName(),
                    jobService.trigger(JobName.DISCOVER, c.getId(), TriggerType.SCHEDULED));
        }
    }

    @Scheduled(cron = "${crawler.schedule.qualify-cron}")
    void qualify() {
        log.info("스케줄 qualify: {}", jobService.trigger(JobName.QUALIFY, null, TriggerType.SCHEDULED));
    }

    @Scheduled(cron = "${crawler.schedule.aggregate-cron}")
    void aggregate() {
        log.info("스케줄 aggregate: {}", jobService.trigger(JobName.AGGREGATE, null, TriggerType.SCHEDULED));
    }
}
