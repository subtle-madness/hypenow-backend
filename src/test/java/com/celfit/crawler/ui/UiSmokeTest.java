package com.celfit.crawler.ui;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.domain.Category;
import com.celfit.crawler.domain.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Import(UiSmokeTest.Config.class)
@Transactional  // 카테고리 저장이 롤백되도록 — 다른 테스트 클래스와 DB 공유
class UiSmokeTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }

        @Bean("jobTaskExecutor") @Primary
        TaskExecutor syncJobExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired CategoryRepository categories;

    @Test
    void 루트는_ui로_리다이렉트() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui"));
    }

    @Test
    void 대시보드와_잡_화면이_뜬다() throws Exception {
        mvc.perform(get("/ui")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("대시보드")));
        mvc.perform(get("/ui/jobs")).andExpect(status().isOk());
        mvc.perform(get("/ui/fragments/runs")).andExpect(status().isOk());
    }

    @Test
    void 잡_실행_폼은_플래시_메시지와_함께_리다이렉트() throws Exception {
        categories.save(new Category("메이크업"));
        mvc.perform(post("/ui/jobs/qualify"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/jobs"))
                .andExpect(flash().attributeExists("message"));
    }

    @Test
    void 카테고리_화면과_폼() throws Exception {
        mvc.perform(get("/ui/categories")).andExpect(status().isOk());
        mvc.perform(post("/ui/categories").param("name", "메이크업"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/categories"));
    }

    @Test
    void 수집_데이터_화면() throws Exception {
        mvc.perform(get("/ui/contents")).andExpect(status().isOk());
        mvc.perform(get("/ui/contents").param("status", "PENDING")).andExpect(status().isOk());
    }
}
