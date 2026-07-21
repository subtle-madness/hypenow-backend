package com.celfit.crawler.crawling.application.port.in;

import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;

/** 수집 잡 트리거 유스케이스 — 웹·스케줄러 어댑터는 이 포트로만 잡을 실행한다. */
public interface TriggerJobUseCase {

    enum TriggerResult { ACCEPTED, BUSY }

    enum StopResult { STOP_REQUESTED, NOT_RUNNING }

    TriggerResult trigger(JobName job, TriggerType triggerType);

    /** requalify=true는 qualify에서 EXCLUDED도 재판정 (raw_profile 재사용 — Apify 재호출 없음). */
    TriggerResult trigger(JobName job, TriggerType triggerType, boolean requalify);

    /**
     * 실행 중인 잡의 협조적 중지 요청 — 진행 중인 단위 작업(키워드·청크·방문·시드)까지 마치고
     * 조기 종료한다. 이미 저장·커밋된 부분 결과는 보존된다.
     */
    StopResult stop(JobName job);
}
