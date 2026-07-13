package com.celfit.crawler.crawling.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.application.service.JobLock;
import com.celfit.crawler.crawling.domain.JobName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 잡 트리거 REST — discover/qualify/collect 세 엔드포인트만 존재(aggregate는 자연 404), category 파라미터는 제거됨. */
@AutoConfigureMockMvc
@Import(JobApiTest.Config.class)
@Transactional
class JobApiTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JobLock jobLock;

    // JobLock은 싱글턴 빈이라 이전 테스트의 비동기 잡이 아직 락을 들고 있을 수 있다 — 매 테스트 전 강제 해제.
    @BeforeEach
    void releaseLocks() {
        for (JobName job : JobName.values()) jobLock.release(job);
    }

    @Test
    void collect_트리거는_ACCEPTED를_반환한다() throws Exception {
        mvc.perform(post("/admin/jobs/collect"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job").value("COLLECT"))
                .andExpect(jsonPath("$.result").value("accepted"));
    }

    @Test
    void discover_트리거는_ACCEPTED를_반환한다() throws Exception {
        mvc.perform(post("/admin/jobs/discover"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job").value("DISCOVER"))
                .andExpect(jsonPath("$.result").value("accepted"));
    }

    @Test
    void qualify_트리거는_requalify_파라미터와_함께_ACCEPTED를_반환한다() throws Exception {
        mvc.perform(post("/admin/jobs/qualify").param("requalify", "true"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job").value("QUALIFY"))
                .andExpect(jsonPath("$.result").value("accepted"));
    }

    @Test
    void aggregate_엔드포인트는_더이상_존재하지_않는다() throws Exception {
        mvc.perform(post("/admin/jobs/aggregate")).andExpect(status().isNotFound());
    }

    @Test
    void category_파라미터는_더이상_바인딩되지_않는다() throws Exception {
        // 예전엔 category가 Long 파라미터라 숫자가 아니면 400이 났다 — 지금은 파라미터 자체가 없어 무시되고 정상 처리된다.
        mvc.perform(post("/admin/jobs/collect").param("category", "not-a-number"))
                .andExpect(status().isAccepted());
    }
}
