package com.celfit.crawler.ui;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.domain.Category;
import com.celfit.crawler.domain.CategoryRepository;
import com.celfit.crawler.domain.Content;
import com.celfit.crawler.domain.ContentRepository;
import com.celfit.crawler.domain.ContentStatus;
import com.celfit.crawler.domain.ContentType;
import com.celfit.crawler.domain.CrawlRun;
import com.celfit.crawler.domain.CrawlRunRepository;
import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.domain.RawPostDetail;
import com.celfit.crawler.domain.RawPostDetailRepository;
import com.celfit.crawler.domain.TriggerType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Import(UiSmokeTest.Config.class)
@Transactional  // 카테고리 저장이 롤백되도록 — 다른 테스트 클래스와 DB 공유
class UiSmokeTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }

        @Bean("jobTaskExecutor") @Primary
        TaskExecutor syncJobExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired CategoryRepository categories;
    @Autowired ContentRepository contents;
    @Autowired CrawlRunRepository crawlRuns;
    @Autowired RawPostDetailRepository rawDetails;

    @Test
    void 루트는_ui로_리다이렉트() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui"));
    }

    @Test
    void 대시보드와_잡_화면이_뜬다() throws Exception {
        mvc.perform(get("/ui")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("대시보드")));
        mvc.perform(get("/ui/jobs")).andExpect(status().isOk());
        mvc.perform(get("/ui/fragments/runs")).andExpect(status().isOk());
    }

    @Test
    void 잡_실행_폼은_플래시_메시지와_함께_리다이렉트() throws Exception {
        categories.save(new Category("메이크업"));
        mvc.perform(post("/ui/jobs/qualify"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/jobs"))
                .andExpect(flash().attributeExists("message"));
    }

    @Test
    void 카테고리_화면과_폼() throws Exception {
        mvc.perform(get("/ui/categories")).andExpect(status().isOk());
        mvc.perform(post("/ui/categories").param("name", "메이크업"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/categories"));
    }

    @Test
    void 키워드_추가는_선택된_대분류_탭으로_돌아온다() throws Exception {
        Category cat = categories.save(new Category("뷰티탭"));
        mvc.perform(post("/ui/categories/" + cat.getId() + "/keywords")
                        .param("keyword", "클렌징폼")
                        .param("subcategory", "클렌징")
                        .param("cat", String.valueOf(cat.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/categories?cat=" + cat.getId()));

        // 선택 탭 화면에 중분류 그룹과 소분류가 보인다
        mvc.perform(get("/ui/categories").param("cat", String.valueOf(cat.getId())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("클렌징폼")));
    }

    @Test
    void 카테고리_삭제_폼은_목록으로_리다이렉트() throws Exception {
        Category saved = categories.save(new Category("삭제용"));
        mvc.perform(post("/ui/categories/" + saved.getId() + "/delete"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/categories"));
        org.junit.jupiter.api.Assertions.assertTrue(categories.findById(saved.getId()).isEmpty());
    }

    @Test
    void 수집_데이터_화면() throws Exception {
        mvc.perform(get("/ui/contents")).andExpect(status().isOk());
        mvc.perform(get("/ui/contents").param("status", "PENDING")).andExpect(status().isOk());
    }

    @Test
    void 음수_페이지도_500이_아니라_정상_렌더링() throws Exception {
        mvc.perform(get("/ui/contents").param("page", "-1")).andExpect(status().isOk());
    }

    @Test
    void 수집_데이터_다중_상태_필터() throws Exception {
        Category cat = categories.save(new Category("필터용"));
        saveContent("sc-pending", cat.getId(), ContentStatus.PENDING);
        saveContent("sc-qualified", cat.getId(), ContentStatus.QUALIFIED);
        saveContent("sc-gone", cat.getId(), ContentStatus.GONE);

        mvc.perform(get("/ui/contents")
                        .param("status", "PENDING")
                        .param("status", "QUALIFIED"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("sc-pending")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("sc-qualified")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sc-gone"))));
    }

    @Test
    void 실행_로그_프래그먼트에_잡_로그가_보인다() throws Exception {
        org.slf4j.LoggerFactory.getLogger("com.celfit.crawler.ui.UiSmokeTest")
                .info("로그패널 스모크 라인");
        mvc.perform(get("/ui/fragments/logs"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그패널 스모크 라인")));
    }

    @Test
    void 콘텐츠_상세에_광고_표시와_태그된_계정이_보인다() throws Exception {
        Category cat = categories.save(new Category("상세용"));
        Content c = new Content("scdetail", ContentType.REELS, "kim",
                Instant.parse("2026-07-01T00:00:00Z"), cat.getId(), "키워드", Instant.now());
        c.setStatus(ContentStatus.AGGREGATED);
        c.setAdMarked(true);
        c = contents.save(c);
        CrawlRun run = crawlRuns.save(new CrawlRun(JobName.AGGREGATE, TriggerType.MANUAL,
                null, null, "actor/x", Instant.now()));
        rawDetails.save(new RawPostDetail(c.getId(), run.getId(),
                Map.of("shortCode", "scdetail", "taggedUsers", List.of(
                        Map.of("username", "banilaco", "full_name", "BANILA CO"))),
                Instant.now()));

        mvc.perform(get("/ui/contents/" + c.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("광고·협찬 표기")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("banilaco")));
    }

    private void saveContent(String shortCode, Long categoryId, ContentStatus status) {
        Content c = new Content(shortCode, ContentType.REELS, "user-" + shortCode,
                Instant.parse("2026-07-01T00:00:00Z"), categoryId, "키워드", Instant.now());
        c.setStatus(status);
        contents.save(c);
    }
}
