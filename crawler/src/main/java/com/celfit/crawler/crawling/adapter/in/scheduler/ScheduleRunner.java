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
        log.info("스케줄 discover: {}", jobService.trigger(JobName.DISCOVER, TriggerType.SCHEDULED));
    }

    @Scheduled(cron = "${crawler.schedule.qualify-cron}")
    void qualify() {
        log.info("스케줄 qualify: {}", jobService.trigger(JobName.QUALIFY, TriggerType.SCHEDULED));
    }

    @Scheduled(cron = "${crawler.schedule.collect-cron}")
    void collect() {
        log.info("스케줄 collect: {}", jobService.trigger(JobName.COLLECT, TriggerType.SCHEDULED));
    }

    /** 데일리 사이클 — collect(스냅샷)와 병렬 가능(잡 락 분리·다른 데이터). */
    @Scheduled(cron = "${crawler.schedule.reels-cron}")
    void reels() {
        log.info("스케줄 reels: {}", jobService.trigger(JobName.REELS, TriggerType.SCHEDULED));
    }

    /**
     * 신규 QUALIFIED 유입분의 뷰티 4분류 — qualify 이후로 배치.
     * rejudge=true — 캡션 0건으로 판정된 계정에 릴스(실측 캡션)가 쌓이면 자동 재판정한다.
     * 이게 꺼져 있으면 뷰티로 잘못 통과한 계정이 영구 고착된다(2026-07-30 오판 886개 실측).
     * 미판정분이 배치 한도를 먼저 쓰고 남은 한도만 재판정에 가므로 신규 판정을 굶기지 않는다.
     */
    @Scheduled(cron = "${crawler.schedule.beauty-cron}")
    void beauty() {
        log.info("스케줄 beauty: {}", jobService.trigger(JobName.BEAUTY, TriggerType.SCHEDULED, true));
    }
}
