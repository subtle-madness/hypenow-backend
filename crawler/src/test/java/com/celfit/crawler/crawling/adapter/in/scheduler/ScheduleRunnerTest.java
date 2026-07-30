package com.celfit.crawler.crawling.adapter.in.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.celfit.crawler.crawling.application.port.in.TriggerJobUseCase;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import org.junit.jupiter.api.Test;

/** 스케줄 크론이 각 잡을 어떤 인자로 트리거하는지 고정하는 단위 테스트. */
class ScheduleRunnerTest {

    TriggerJobUseCase jobService = mock(TriggerJobUseCase.class);

    ScheduleRunner runner = new ScheduleRunner(jobService);

    @Test
    void 야간_beauty_크론은_재판정을_켜서_트리거한다() {
        runner.beauty();

        verify(jobService).trigger(JobName.BEAUTY, TriggerType.SCHEDULED, true);
    }

    @Test
    void 야간_qualify_크론은_재판정을_켜지_않는다() {
        runner.qualify();

        verify(jobService).trigger(JobName.QUALIFY, TriggerType.SCHEDULED);
    }
}
