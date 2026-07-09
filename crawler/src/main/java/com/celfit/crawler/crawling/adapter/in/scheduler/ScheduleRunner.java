package com.celfit.crawler.crawling.adapter.in.scheduler;

import com.celfit.crawler.crawling.application.port.in.TriggerJobUseCase;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
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

    private final TriggerJobUseCase jobService;

    public ScheduleRunner(TriggerJobUseCase jobService) {
        this.jobService = jobService;
    }

    @Scheduled(cron = "${crawler.schedule.discover-cron}")
    void discover() {
        // categoryId=null → JobService가 전체 활성 카테고리를 잡 1회 안에서 순차 실행
        log.info("스케줄 discover: {}", jobService.trigger(JobName.DISCOVER, null, TriggerType.SCHEDULED));
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
