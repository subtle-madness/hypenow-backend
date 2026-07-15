package com.celfit.crawler.content.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.content.application.port.out.SearchKeywordRepository;
import com.celfit.crawler.content.domain.SearchKeyword;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 검색 키워드 REST — 카테고리 계층 제거 후 평탄화된 CRUD. 텍스트 수정은 지원하지 않음. */
@AutoConfigureMockMvc
@Transactional
class KeywordApiTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired SearchKeywordRepository keywords;

    @Test
    void 추가_목록_토글_삭제_왕복() throws Exception {
        mvc.perform(post("/admin/keywords")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\": \"데일리룩\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.keyword").value("데일리룩"))
                .andExpect(jsonPath("$.enabled").value(true));

        Long id = keywords.findByKeyword("데일리룩").orElseThrow().getId();

        mvc.perform(get("/admin/keywords"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.keyword=='데일리룩')].enabled").value(true));

        mvc.perform(put("/admin/keywords/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        assertThat(keywords.findById(id).orElseThrow().isEnabled()).isFalse();

        mvc.perform(delete("/admin/keywords/" + id))
                .andExpect(status().isNoContent());
        assertThat(keywords.findById(id)).isEmpty();
    }

    @Test
    void 키워드_텍스트_수정은_지원하지_않는다() throws Exception {
        SearchKeyword saved = keywords.save(new SearchKeyword("뷰티꿀팁", Instant.now()));

        // PUT은 enabled 전용 — keyword만 보내고 enabled를 빼면(=텍스트 수정 시도) 400
        mvc.perform(put("/admin/keywords/" + saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\": \"새이름\"}"))
                .andExpect(status().isBadRequest());

        assertThat(keywords.findById(saved.getId()).orElseThrow().getKeyword()).isEqualTo("뷰티꿀팁");
    }

    @Test
    void 중복_키워드_추가는_400() throws Exception {
        keywords.save(new SearchKeyword("중복키워드", Instant.now()));

        mvc.perform(post("/admin/keywords")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\": \"중복키워드\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 없는_키워드_토글은_404() throws Exception {
        mvc.perform(put("/admin/keywords/999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isNotFound());
    }
}
