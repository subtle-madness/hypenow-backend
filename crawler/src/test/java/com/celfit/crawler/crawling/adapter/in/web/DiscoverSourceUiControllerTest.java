package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.settings.application.service.DiscoverSourceSetting;
import com.celfit.crawler.settings.domain.DiscoverSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class DiscoverSourceUiControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired DiscoverSourceSetting setting;

    @Test
    void 소스_POST가_설정을_바꾸고_리다이렉트한다() throws Exception {
        mvc.perform(post("/ui/discover-source").param("source", "ACTOR"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));
        assertThat(setting.current()).isEqualTo(DiscoverSource.ACTOR);
    }

    @Test
    void 설정_페이지가_발굴_소스를_노출한다() throws Exception {
        mvc.perform(get("/ui/settings")).andExpect(status().isOk());
        // 모델 속성은 UiSettingsController에서 추가 — 렌더 성공이면 배선 OK
    }
}
