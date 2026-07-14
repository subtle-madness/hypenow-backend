package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.settings.application.service.ProxySourceSetting;
import com.celfit.crawler.settings.domain.ProxySource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class ProxySourceUiControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProxySourceSetting setting;

    @Test
    void 프록시_소스_POST가_설정을_바꾸고_리다이렉트한다() throws Exception {
        mvc.perform(post("/ui/proxy-source").param("source", "DATAIMPULSE_MOBILE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));
        assertThat(setting.current()).isEqualTo(ProxySource.DATAIMPULSE_MOBILE);
    }
}
