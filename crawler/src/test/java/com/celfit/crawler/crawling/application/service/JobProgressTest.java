package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.domain.JobName;
import org.junit.jupiter.api.Test;

class JobProgressTest {

    @Test
    void start_advance_로_진행률이_누적되고_percent가_계산된다() {
        var p = new JobProgress();
        p.start(JobName.COLLECT, 200);
        p.advance(JobName.COLLECT, 50);
        p.advance(JobName.COLLECT, 10);

        var v = p.get(JobName.COLLECT);
        assertThat(v.current()).isEqualTo(60);
        assertThat(v.total()).isEqualTo(200);
        assertThat(v.percent()).isEqualTo(30);
    }

    @Test
    void finish하면_진행상태가_사라진다() {
        var p = new JobProgress();
        p.start(JobName.DISCOVER, 5);
        p.finish(JobName.DISCOVER);
        assertThat(p.get(JobName.DISCOVER)).isNull();
    }

    @Test
    void total이_0이면_percent는_0() {
        var p = new JobProgress();
        p.start(JobName.QUALIFY, 0);
        assertThat(p.get(JobName.QUALIFY).percent()).isZero();
    }
}
