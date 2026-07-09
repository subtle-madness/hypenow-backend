package com.celfit.crawler.crawling.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** 기본(enabled=false)에서는 스케줄 빈이 아예 없다. */
class ScheduleRunnerDisabledTest extends IntegrationTest {

    @Autowired ApplicationContext ctx;

    @Test
    void 스케줄러_빈이_없다() {
        assertThat(ctx.getBeansOfType(ScheduleRunner.class)).isEmpty();
    }
}
