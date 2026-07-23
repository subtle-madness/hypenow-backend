package com.celfit.crawler.crawling.adapter.in.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.crawling.application.service.ManualDiscoveryService;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 수동 발굴 등록 API — 토큰 인증·정상 등록·중복·형식 불량. */
@WebMvcTest(controllers = ManualDiscoveryController.class,
        properties = "crawler.manual-discovery.token=test-token")
class ManualDiscoveryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ManualDiscoveryService service;

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(post("/api/manual-discoveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"abc\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 토큰이_틀리면_401() throws Exception {
        mockMvc.perform(post("/api/manual-discoveries")
                        .header("X-Api-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"abc\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 신규_등록이면_created_true와_상태를_돌려준다() throws Exception {
        given(service.register("new.user")).willReturn(new ManualDiscoveryService.Result(
                "new.user", true, InfluencerStatus.DISCOVERED, null));

        mockMvc.perform(post("/api/manual-discoveries")
                        .header("X-Api-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new.user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("new.user"))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.status").value("DISCOVERED"))
                .andExpect(jsonPath("$.beautyClass").doesNotExist());
    }

    @Test
    void 기존_계정이면_created_false와_뷰티분류를_돌려준다() throws Exception {
        given(service.register("known.user")).willReturn(new ManualDiscoveryService.Result(
                "known.user", false, InfluencerStatus.QUALIFIED, BeautyClass.INFLUENCER));

        mockMvc.perform(post("/api/manual-discoveries")
                        .header("X-Api-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"known.user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.status").value("QUALIFIED"))
                .andExpect(jsonPath("$.beautyClass").value("INFLUENCER"));
    }

    @Test
    void 형식_불량이면_400() throws Exception {
        given(service.register(anyString())).willThrow(new IllegalArgumentException("username 형식 불량: !!"));

        mockMvc.perform(post("/api/manual-discoveries")
                        .header("X-Api-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"!!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
