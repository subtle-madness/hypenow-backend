package com.celfit.crawler.crawling.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "crawler.schedule.enabled=true")
class ScheduleRunnerEnabledTest extends IntegrationTest {

    @Autowired ApplicationContext ctx;

    @Test
    void 스케줄러_빈이_뜬다() {
        assertThat(ctx.getBeansOfType(ScheduleRunner.class)).hasSize(1);
    }
}
