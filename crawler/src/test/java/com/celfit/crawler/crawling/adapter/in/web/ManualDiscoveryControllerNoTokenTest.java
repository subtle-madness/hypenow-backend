package com.celfit.crawler.crawling.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.crawling.application.service.ManualDiscoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 토큰 미설정이면 올바른 토큰을 보내도 API 전체 비활성(503) — fail-closed. */
@WebMvcTest(controllers = ManualDiscoveryController.class)
class ManualDiscoveryControllerNoTokenTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ManualDiscoveryService service;

    @Test
    void 토큰_미설정이면_503() throws Exception {
        mockMvc.perform(post("/api/manual-discoveries")
                        .header("X-Api-Token", "anything")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"abc\"}"))
                .andExpect(status().isServiceUnavailable());
    }
}
