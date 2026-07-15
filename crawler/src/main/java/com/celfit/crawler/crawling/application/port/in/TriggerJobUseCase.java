package com.celfit.crawler.crawling.application.port.in;

import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;

/** 수집 잡 트리거 유스케이스 — 웹·스케줄러 어댑터는 이 포트로만 잡을 실행한다. */
public interface TriggerJobUseCase {

    enum TriggerResult { ACCEPTED, BUSY }

    TriggerResult trigger(JobName job, TriggerType triggerType);

    /** requalify=true는 qualify에서 EXCLUDED도 재판정 (raw_profile 재사용 — Apify 재호출 없음). */
    TriggerResult trigger(JobName job, TriggerType triggerType, boolean requalify);
}
