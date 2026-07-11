package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.settings.application.service.ProfileSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSupplementSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class ProfileSourceUiControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProfileSourceSetting sourceSetting;
    @Autowired ProfileSupplementSetting supplementSetting;

    @Test
    void 소스_POST가_설정을_바꾸고_리다이렉트한다() throws Exception {
        mvc.perform(post("/ui/profile-source").param("source", "HIKER_MOBILE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));
        assertThat(sourceSetting.current()).isEqualTo(ProfileSource.HIKER_MOBILE);
    }

    @Test
    void 보충_체크박스_값이_저장된다() throws Exception {
        mvc.perform(post("/ui/profile-source")
                        .param("source", "SELF")
                        .param("posts", "true")
                        .param("related", "true"))
                .andExpect(status().is3xxRedirection());
        assertThat(supplementSetting.postsEnabled()).isTrue();
        assertThat(supplementSetting.relatedEnabled()).isTrue();
    }
}
