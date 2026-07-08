package com.celfit.crawler.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.domain.CrawlRun;
import com.celfit.crawler.domain.CrawlRunRepository;
import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.domain.TriggerType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@Transactional
class CategoryApiTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired CrawlRunRepository crawlRuns;

    long createCategory(String name) throws Exception {
        String body = mvc.perform(post("/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("id").asLong();
    }

    @Test
    void 카테고리_키워드_규칙_CRUD_왕복() throws Exception {
        long catId = createCategory("메이크업");

        // 중복 생성 → 409
        mvc.perform(post("/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"메이크업\"}"))
                .andExpect(status().isConflict());

        // 키워드 추가
        mvc.perform(post("/admin/categories/" + catId + "/keywords")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\": \"화장품추천\"}"))
                .andExpect(status().isCreated());

        // 규칙 업서트
        mvc.perform(put("/admin/categories/" + catId + "/rule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minFollowers\": 5000, \"maxFollowers\": null, \"contentTypes\": \"REELS\"}"))
                .andExpect(status().isOk());

        // 목록에 반영 확인
        mvc.perform(get("/admin/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("메이크업"))
                .andExpect(jsonPath("$[0].keywords[0].keyword").value("화장품추천"))
                .andExpect(jsonPath("$[0].rule.minFollowers").value(5000))
                .andExpect(jsonPath("$[0].rule.contentTypes").value("REELS"));

        // 카테고리 비활성화
        mvc.perform(patch("/admin/categories/" + catId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/admin/categories"))
                .andExpect(jsonPath("$[0].enabled").value(false));
    }

    @Test
    void 카테고리_삭제_키워드_규칙도_함께_제거() throws Exception {
        long catId = createCategory("삭제대상");
        mvc.perform(post("/admin/categories/" + catId + "/keywords")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\": \"임시키워드\"}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/admin/categories/" + catId + "/rule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minFollowers\": 1000, \"maxFollowers\": null, \"contentTypes\": \"ALL\"}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/admin/categories/" + catId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/admin/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void 수집_이력_있는_카테고리는_삭제_거부() throws Exception {
        long catId = createCategory("이력있음");
        crawlRuns.save(new CrawlRun(JobName.DISCOVER, TriggerType.MANUAL, catId,
                "키워드", "actor/x", Instant.now()));

        mvc.perform(delete("/admin/categories/" + catId))
                .andExpect(status().isConflict());

        mvc.perform(get("/admin/categories"))
                .andExpect(jsonPath("$[0].name").value("이력있음"));
    }

    @Test
    void 없는_카테고리_삭제_404() throws Exception {
        mvc.perform(delete("/admin/categories/99999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 키워드_토글() throws Exception {
        long catId = createCategory("스킨케어");
        String body = mvc.perform(post("/admin/categories/" + catId + "/keywords")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\": \"피부관리\"}"))
                .andReturn().getResponse().getContentAsString();
        long kwId = new ObjectMapper().readTree(body).get("id").asLong();

        mvc.perform(patch("/admin/keywords/" + kwId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/admin/categories"))
                .andExpect(jsonPath("$[0].keywords[0].enabled").value(false));
    }
}
