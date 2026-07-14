package com.celfit.crawler.dashboard.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentOrigin;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.CrawlRunRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawCommentRepository;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawComment;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최소 UI 스모크 — 제거된 도메인 필드를 템플릿이 더 이상 참조하지 않는지 렌더로 확인.
 * Task 10이 UI를 재편하면 그때 확장된 스모크로 대체된다.
 */
@AutoConfigureMockMvc
@Transactional  // 시드가 롤백되도록 — 다른 테스트 클래스와 DB 공유(싱글턴 컨테이너)
class UiSmokeTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired InfluencerRepository influencers;
    @Autowired ContentRepository contents;
    @Autowired RawCommentRepository rawComments;
    @Autowired CrawlRunRepository crawlRuns;

    @Test
    void 대시보드가_렌더된다() throws Exception {
        mvc.perform(get("/ui")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("대시보드")));
    }

    @Test
    void 상태_타일_프래그먼트가_렌더된다() throws Exception {
        mvc.perform(get("/ui/fragments/status-tiles")).andExpect(status().isOk());
    }

    @Test
    void 잡_화면이_렌더된다() throws Exception {
        mvc.perform(get("/ui/jobs")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("collect")));
    }

    @Test
    void 잡_화면에_예상_비용_카드가_렌더된다() throws Exception {
        mvc.perform(get("/ui/jobs")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("예상 비용")));
    }

    @Test
    void 수집_데이터_화면이_content_행이_있어도_렌더된다() throws Exception {
        Influencer inf = influencers.save(new Influencer("smoke-user"));
        contents.save(new Content("sc-smoke", ContentType.REELS, "smoke-user",
                inf.getId(), Instant.parse("2026-07-01T00:00:00Z"), Instant.now(), ContentOrigin.ENUMERATION));

        // 행이 존재하는 상태에서 렌더 — 제거된 필드(adMarked/mainGroup 등) 참조가 남아 있으면 여기서 터진다
        mvc.perform(get("/ui/contents")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("sc-smoke")));
    }

    @Test
    void 콘텐츠_상세_화면이_페이지형_raw_comment_행이_있어도_렌더되고_빈_행을_나열하지_않는다() throws Exception {
        Influencer inf = influencers.save(new Influencer("smoke-detail-user"));
        Content content = contents.save(new Content("sc-detail-smoke", ContentType.FEED, "smoke-detail-user",
                inf.getId(), Instant.parse("2026-07-01T00:00:00Z"), Instant.now(), ContentOrigin.ENUMERATION));
        CrawlRun run = crawlRuns.save(new CrawlRun(JobName.COLLECT, TriggerType.MANUAL, null,
                "smoke-detail-user", "direct-comment-crawler", Instant.now()));
        // SELF_GQL 신규 수집분 — writer/text/writtenAt은 설계상 NULL, payload만 페이지 원형으로 채워진다.
        rawComments.save(new RawComment(content.getId(), run.getId(), RawSource.SELF_GQL,
                Map.of("data", Map.of("edges", java.util.List.of("c1", "c2"))), Instant.now()));

        mvc.perform(get("/ui/contents/" + content.getId())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("sc-detail-smoke")))
                // fallback 행이 payload를 pretty JSON으로 담아 렌더 — "빈 행 무한 나열"이 아님을 확인
                .andExpect(content().string(org.hamcrest.Matchers.containsString("edges")));
    }

    @Test
    void 검색_키워드_화면이_렌더된다() throws Exception {
        mvc.perform(get("/ui/keywords")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("검색 키워드")));
    }

    @Test
    void 실행_이력_프래그먼트에_요청수_기준_비용이_렌더된다() throws Exception {
        CrawlRun run = crawlRuns.save(new CrawlRun(JobName.DISCOVER, TriggerType.MANUAL,
                "cost-smoke-kw", null, "hiker-hashtag-top", Instant.now()));
        run.finishOk(null, 12, 3, Instant.now());
        crawlRuns.save(run);

        // requestCount=12 × $0.001/요청 = $0.012
        mvc.perform(get("/ui/fragments/runs")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("$0.012")));
    }

    @Test
    void 실행_이력에_구_파이프라인_AGGREGATE_행이_있어도_렌더된다() throws Exception {
        // V8 이관은 crawl_run의 과거 job 값을 재매핑하지 않는다 — 실DB에 AGGREGATE 이력 54건 존재.
        // enum에서 AGGREGATE를 지우면 이력 조회가 IllegalArgumentException으로 터졌던 회귀의 재현.
        crawlRuns.save(new CrawlRun(JobName.AGGREGATE, TriggerType.MANUAL,
                null, null, "legacy-actor", Instant.now()));

        mvc.perform(get("/ui/fragments/runs")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AGGREGATE")));
    }

    @Test
    void 대시보드_상태_타일에_발굴_보관_부산물_건수가_렌더된다() throws Exception {
        // 발굴 부산물(DISCOVERY) — 게시물 수집 카드(ENUMERATION 기준) 집계엔 안 잡히고
        // "발굴 보관" 참고용 총계에만 잡혀야 한다.
        Influencer inf = influencers.save(new Influencer("smoke-discovery-user"));
        contents.save(new Content("sc-discovery-smoke", ContentType.FEED, "smoke-discovery-user",
                inf.getId(), Instant.parse("2026-07-01T00:00:00Z"), Instant.now(), ContentOrigin.DISCOVERY));

        mvc.perform(get("/ui/fragments/status-tiles")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("발굴 보관")));
    }

    @Test
    void 대시보드_상태_타일에_인플루언서_카운트가_렌더된다() throws Exception {
        long before = influencers.countByStatus(InfluencerStatus.DISCOVERED);
        influencers.save(new Influencer("smoke-count-user"));

        mvc.perform(get("/ui/fragments/status-tiles")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DISCOVERED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(String.valueOf(before + 1))));
    }
}
