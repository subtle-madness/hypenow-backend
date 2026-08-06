package com.celfit.crawler.settings.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.settings.application.service.DiscoverSourceSetting;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.settings.domain.DiscoverSource;
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
    @Autowired
    DiscoverSourceSetting discoverSourceSetting;

    @BeforeEach
    void resetFake() {
        fake.reset();
        discoverSourceSetting.update(DiscoverSource.ACTOR);
    }

    @Test
    void 기본값_조회() throws Exception {
        mvc.perform(get("/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[?(@.key=='discover.results-limit')].effective").value(100))
                .andExpect(jsonPath("$[?(@.key=='discover.results-limit')].defaultValue").value(100))
                .andExpect(jsonPath("$[?(@.key=='discover.results-limit')].overridden").value(false))
                .andExpect(jsonPath("$[?(@.key=='qualify.batch-limit')].defaultValue").value(500))
                .andExpect(jsonPath("$[?(@.key=='qualify.min-followers')].defaultValue").value(3000))
                .andExpect(jsonPath("$[?(@.key=='qualify.max-followers')].defaultValue").value(50000))
                .andExpect(jsonPath("$[?(@.key=='collect.batch-limit')].defaultValue").value(10))
                .andExpect(jsonPath("$[?(@.key=='collect.revisit-interval-days')].defaultValue").value(7))
                .andExpect(jsonPath("$[?(@.key=='similar.batch-limit')].defaultValue").value(50))
                .andExpect(jsonPath("$[?(@.key=='beauty.batch-limit')].defaultValue").value(500))
                .andExpect(jsonPath("$[?(@.key=='reels.batch-limit')].defaultValue").value(10))
                .andExpect(jsonPath("$[?(@.key=='reels.actor-results-limit')].defaultValue").value(6));
    }

    @Test
    void 새_키_왕복() throws Exception {
        mvc.perform(put("/admin/settings/qualify.min-followers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(5000))
                .andExpect(jsonPath("$.overridden").value(true));
        assertThat(settingsService.qualifyMinFollowers()).isEqualTo(5000);

        mvc.perform(put("/admin/settings/qualify.max-followers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 80000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(80000));
        assertThat(settingsService.qualifyMaxFollowers()).isEqualTo(80000);

        mvc.perform(put("/admin/settings/collect.revisit-interval-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 14}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(14));
        assertThat(settingsService.revisitIntervalDays()).isEqualTo(14);
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
    }

    @Test
    void 리셋하면_기본값으로_복귀() throws Exception {
        mvc.perform(put("/admin/settings/collect.batch-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 20}"))
                .andExpect(status().isOk());
        assertThat(settingsService.collectBatchLimit()).isEqualTo(20);

        mvc.perform(put("/admin/settings/collect.batch-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(10))
                .andExpect(jsonPath("$.overridden").value(false));
        assertThat(settingsService.collectBatchLimit()).isEqualTo(10);
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

        // aggregate.* 키는 제거됨 — 더 이상 알려진 키가 아니므로 400
        mvc.perform(put("/admin/settings/aggregate.delay-days")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/admin/settings/aggregate.batch-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 5}"))
                .andExpect(status().isBadRequest());
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
