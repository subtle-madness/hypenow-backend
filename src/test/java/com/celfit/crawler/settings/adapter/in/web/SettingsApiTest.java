package com.celfit.crawler.settings.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.content.domain.Category;
import com.celfit.crawler.content.domain.CategoryKeyword;
import com.celfit.crawler.content.application.port.out.CategoryKeywordRepository;
import com.celfit.crawler.content.application.port.out.CategoryRepository;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.crawling.application.service.DiscoverJob;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Import(SettingsApiTest.Config.class)
@Transactional
class SettingsApiTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired SettingsService settingsService;
    @Autowired FakeApifyRunner fake;
    @Autowired DiscoverJob discoverJob;
    @Autowired CategoryRepository categories;
    @Autowired CategoryKeywordRepository keywords;

    @BeforeEach
    void resetFake() {
        fake.reset();
    }

    @Test
    void 기본값_조회() throws Exception {
        mvc.perform(get("/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[?(@.key=='discover.results-limit')].effective").value(100))
                .andExpect(jsonPath("$[?(@.key=='discover.results-limit')].defaultValue").value(100))
                .andExpect(jsonPath("$[?(@.key=='discover.results-limit')].overridden").value(false));
    }

    @Test
    void 오버라이드가_효과값과_잡_입력에_반영된다() throws Exception {
        mvc.perform(put("/admin/settings/discover.results-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(5))
                .andExpect(jsonPath("$.overridden").value(true));

        assertThat(settingsService.resultsLimit()).isEqualTo(5);

        Long catId = categories.save(new Category("메이크업")).getId();
        keywords.save(new CategoryKeyword(catId, "메이크업"));
        fake.enqueue(List.of(Map.of("shortCode", "sc1", "productType", "clips",
                "timestamp", "2026-07-01T12:00:00.000Z", "ownerUsername", "kim")));

        discoverJob.run(catId, TriggerType.MANUAL);

        assertThat(fake.calls.get(0).input()).containsEntry("resultsLimit", 5);
    }

    @Test
    void 리셋하면_기본값으로_복귀() throws Exception {
        mvc.perform(put("/admin/settings/aggregate.batch-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 10}"))
                .andExpect(status().isOk());
        assertThat(settingsService.batchLimit()).isEqualTo(10);

        mvc.perform(put("/admin/settings/aggregate.batch-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(200))
                .andExpect(jsonPath("$.overridden").value(false));
        assertThat(settingsService.batchLimit()).isEqualTo(200);
    }

    @Test
    void 검증_실패() throws Exception {
        mvc.perform(put("/admin/settings/unknown-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 5}"))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/admin/settings/discover.results-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 0}"))
                .andExpect(status().isBadRequest());

        // delay-days는 0 허용 (지연 없음)
        mvc.perform(put("/admin/settings/aggregate.delay-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 0}"))
                .andExpect(status().isOk());
    }

    @Test
    void UI_설정_화면_조회와_저장() throws Exception {
        mvc.perform(get("/ui/settings")).andExpect(status().isOk());

        mvc.perform(post("/ui/settings")
                        .param("discover.results-limit", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"))
                .andExpect(flash().attributeExists("message"));

        assertThat(settingsService.resultsLimit()).isEqualTo(7);
    }
}
