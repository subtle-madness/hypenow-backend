package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 판정 잡 — 인플루언서 중심 재구현은 Task 7에서 진행. 현재는 빈 셸. */
@Service
public class QualifyJob {

    public record Summary(int profiled, int qualified, int excluded, int deferred) {}

    @Transactional
    public Summary run(TriggerType trigger) {
        return run(trigger, false);
    }

    /** requalify=true면 EXCLUDED도 재판정 (Task 7에서 구현). */
    @Transactional
    public Summary run(TriggerType trigger, boolean requalify) {
        return new Summary(0, 0, 0, 0);
    }
}
