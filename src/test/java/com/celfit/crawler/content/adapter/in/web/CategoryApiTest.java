package com.celfit.crawler.content.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.application.port.out.CrawlRunRepository;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
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
    void 대분류_중분류_단위로_소분류_enabled를_일괄_토글() throws Exception {
        long catId = createCategory("립뷰티");
        addGroupedKeyword(catId, "톤업틴트", "틴트", "립메이크업");
        addGroupedKeyword(catId, "물틴트", "틴트", "립메이크업");
        addGroupedKeyword(catId, "매트립", "립스틱", "립메이크업");

        // 대분류 립메이크업 전체 제외 → 소분류 3개 모두 enabled=false
        mvc.perform(patch("/admin/categories/" + catId + "/groups")
                        .param("mainGroup", "립메이크업")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\": false}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/admin/categories"))
                .andExpect(jsonPath("$[0].keywords[0].keyword").value("톤업틴트"))
                .andExpect(jsonPath("$[0].keywords[0].enabled").value(false))
                .andExpect(jsonPath("$[0].keywords[1].enabled").value(false))
                .andExpect(jsonPath("$[0].keywords[2].enabled").value(false));

        // 중분류 틴트만 다시 포함 → 틴트 2개 true, 립스틱 1개는 여전히 false
        mvc.perform(patch("/admin/categories/" + catId + "/groups")
                        .param("mainGroup", "립메이크업").param("subcategory", "틴트")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\": true}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/admin/categories"))
                .andExpect(jsonPath("$[0].keywords[0].enabled").value(true))   // 톤업틴트(틴트)
                .andExpect(jsonPath("$[0].keywords[1].enabled").value(true))   // 물틴트(틴트)
                .andExpect(jsonPath("$[0].keywords[2].enabled").value(false)); // 매트립(립스틱)
    }

    void addGroupedKeyword(long catId, String kw, String sub, String main) throws Exception {
        mvc.perform(post("/admin/categories/" + catId + "/keywords")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"" + kw + "\",\"subcategory\":\"" + sub
                                + "\",\"mainGroup\":\"" + main + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void 키워드에_대분류_중분류를_지정하고_생략하면_아래_단계값이_승계된다() throws Exception {
        long catId = createCategory("뷰티");

        // 대분류·중분류 전부 지정
        mvc.perform(post("/admin/categories/" + catId + "/keywords")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\": \"시트마스크\", \"subcategory\": \"시트팩\", \"mainGroup\": \"마스크팩\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mainGroup").value("마스크팩"))
                .andExpect(jsonPath("$.subcategory").value("시트팩"));

        // 전부 생략 → 키워드가 중분류·대분류까지 승계
        mvc.perform(post("/admin/categories/" + catId + "/keywords")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\": \"립밤\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subcategory").value("립밤"))
                .andExpect(jsonPath("$.mainGroup").value("립밤"));

        mvc.perform(get("/admin/categories"))
                .andExpect(jsonPath("$[0].keywords[0].mainGroup").value("마스크팩"));
    }

    @Test
    void 소분류_중분류_대분류_단위로_삭제할_수_있다() throws Exception {
        long catId = createCategory("뷰티");
        long kwId = addKeyword(catId, "시트마스크", "시트팩", "마스크팩");
        addKeyword(catId, "겔마스크", "시트팩", "마스크팩");
        addKeyword(catId, "코팩패치", "코팩", "마스크팩");
        addKeyword(catId, "클렌징폼", "클렌징", "클렌징");

        // 소분류 1개 삭제
        mvc.perform(delete("/admin/keywords/" + kwId)).andExpect(status().isNoContent());
        mvc.perform(get("/admin/categories"))
                .andExpect(jsonPath("$[0].keywords.length()").value(3));

        // 중분류 삭제 → 시트팩 하위 전부 제거
        mvc.perform(delete("/admin/categories/" + catId + "/groups")
                        .param("mainGroup", "마스크팩").param("subcategory", "시트팩"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/admin/categories"))
                .andExpect(jsonPath("$[0].keywords.length()").value(2));

        // 대분류 삭제 → 마스크팩 하위 전부 제거, 다른 대분류는 유지
        mvc.perform(delete("/admin/categories/" + catId + "/groups")
                        .param("mainGroup", "마스크팩"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/admin/categories"))
                .andExpect(jsonPath("$[0].keywords.length()").value(1))
                .andExpect(jsonPath("$[0].keywords[0].keyword").value("클렌징폼"));
    }

    long addKeyword(long catId, String keyword, String sub, String main) throws Exception {
        String body = mvc.perform(post("/admin/categories/" + catId + "/keywords")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\": \"" + keyword + "\", \"subcategory\": \"" + sub
                                + "\", \"mainGroup\": \"" + main + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("id").asLong();
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
