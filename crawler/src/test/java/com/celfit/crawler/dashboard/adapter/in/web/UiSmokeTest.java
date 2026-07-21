package com.celfit.crawler.dashboard.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentOrigin;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.CrawlRunRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerDiscoveryRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawCommentRepository;
import com.celfit.crawler.crawling.application.port.out.RawDiscoveryPostRepository;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerDiscovery;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawComment;
import com.celfit.crawler.crawling.domain.RawDiscoveryPost;
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
    @Autowired InfluencerDiscoveryRepository discoveries;
    @Autowired RawDiscoveryPostRepository rawDiscovery;
    @Autowired com.celfit.crawler.dashboard.application.StatusService statusService;

    @Test
    void 대시보드가_렌더된다() throws Exception {
        mvc.perform(get("/ui")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("대시보드")));
    }

    @Test
    void 대시보드_상단에_파이프라인_다이어그램이_렌더된다() throws Exception {
        // 잡 흐름(발굴→판정→뷰티 판정→수집 3갈래 + 유사발굴 루프)을 정적 다이어그램으로 노출.
        // 재스냅샷 루프는 기능 제거(2026-07-18 — qualify가 SELF 캡션 재료로 첫 판정)와 함께 빠졌다.
        mvc.perform(get("/ui")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pipeline-map")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("유사 발굴")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("resnapshot"))));
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
    void 인플루언서_명단은_판정_완료만_최초_발굴_맥락과_함께_보여준다() throws Exception {
        Influencer qualified = new Influencer("smoke-roster-qualified");
        qualified.setStatus(InfluencerStatus.QUALIFIED);
        qualified.setFollowers(12345L);
        influencers.save(qualified);
        Influencer excluded = new Influencer("smoke-roster-excluded");
        excluded.setStatus(InfluencerStatus.EXCLUDED);
        influencers.save(excluded);
        influencers.save(new Influencer("smoke-roster-discovered")); // 판정 전 — 명단 밖

        // 발굴 이력 2건 — 명단에는 최초 발굴(먼저 저장된 행)의 키워드만 붙는다
        discoveries.save(new InfluencerDiscovery(qualified.getId(), "roster-first-kw",
                "sc-roster-1", Instant.parse("2026-07-01T00:00:00Z")));
        discoveries.save(new InfluencerDiscovery(qualified.getId(), "roster-later-kw",
                "sc-roster-2", Instant.parse("2026-07-10T00:00:00Z")));

        mvc.perform(get("/ui/influencers")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("smoke-roster-qualified")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("smoke-roster-excluded")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("roster-first-kw")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("roster-later-kw"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("smoke-roster-discovered"))));
    }

    @Test
    void 인플루언서_명단은_v2_4분류_판정을_배지로_렌더한다() throws Exception {
        // v2 판정(beautyClass 세팅) 렌더 경로 커버 — 이 브랜치의 Thymeleaf 표현식(체이닝 삼항)에서
        // 실제 파스 버그가 났던 이력이 있어, boolean 폴백만 있는 다른 픽스처로는 회귀를 못 잡는다.
        Influencer inf = new Influencer("smoke-v2-beautyclass");
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.classify(com.celfit.crawler.crawling.domain.BeautyClass.BEAUTY_SERVICE,
                Influencer.BEAUTY_SOURCE_CLAUDE, "시술 중심 계정");
        influencers.save(inf);

        mvc.perform(get("/ui/influencers")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("smoke-v2-beautyclass")))
                // 4분류 배지 텍스트 + 배지 색상 클래스
                .andExpect(content().string(org.hamcrest.Matchers.containsString("시술·서비스")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BEAUTY_SERVICE")));
    }

    @Test
    void 인플루언서_명단_상태_필터가_동작하고_판정_외_상태는_무시된다() throws Exception {
        Influencer qualified = new Influencer("smoke-filter-qualified");
        qualified.setStatus(InfluencerStatus.QUALIFIED);
        influencers.save(qualified);
        Influencer excluded = new Influencer("smoke-filter-excluded");
        excluded.setStatus(InfluencerStatus.EXCLUDED);
        influencers.save(excluded);

        mvc.perform(get("/ui/influencers").param("status", "QUALIFIED"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("smoke-filter-qualified")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("smoke-filter-excluded"))));

        // DISCOVERED는 명단 범위 밖 — 파라미터로 들어와도 무시되어 판정 완료 전체가 나온다
        influencers.save(new Influencer("smoke-filter-discovered"));
        mvc.perform(get("/ui/influencers").param("status", "DISCOVERED"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("smoke-filter-qualified")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("smoke-filter-excluded")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("smoke-filter-discovered"))));
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
    void 실행_이력의_발굴_건수와_중복이_게시물이_아니라_인플루언서_기준이다() throws Exception {
        // 시나리오: run1에서 dup_inf 발굴 → run2에서 dup_inf 재발굴 + new_inf 신규 발굴 (게시물 2건).
        // run2의 건수는 게시물 2건이 아니라 "인플루언서 2명", 중복은 "이미 발굴됐던 인플루언서 1명"이다.
        Instant run1Start = Instant.parse("2026-07-14T00:00:00Z");
        Instant run2Start = Instant.parse("2026-07-14T01:00:00Z");

        Influencer dupInf = influencers.save(new Influencer("smoke-dup-inf"));
        Influencer newInf = influencers.save(new Influencer("smoke-new-inf"));
        Content cDup = contents.save(new Content("sc-runstat-dup", ContentType.FEED, "smoke-dup-inf",
                dupInf.getId(), run1Start, run1Start, ContentOrigin.DISCOVERY));
        Content cNew = contents.save(new Content("sc-runstat-new", ContentType.FEED, "smoke-new-inf",
                newInf.getId(), run2Start, run2Start, ContentOrigin.DISCOVERY));

        CrawlRun run1 = crawlRuns.save(new CrawlRun(JobName.DISCOVER, TriggerType.MANUAL,
                "runstat-kw", null, "hiker-hashtag-top", run1Start));
        run1.finishOk(null, 4, 1, run1Start.plusSeconds(30));
        crawlRuns.save(run1);
        CrawlRun run2 = crawlRuns.save(new CrawlRun(JobName.DISCOVER, TriggerType.MANUAL,
                "runstat-kw", null, "hiker-hashtag-top", run2Start));
        run2.finishOk(null, 4, 2, run2Start.plusSeconds(30));
        crawlRuns.save(run2);

        discoveries.save(new InfluencerDiscovery(dupInf.getId(), "runstat-kw", "sc-runstat-dup", run1Start.plusSeconds(1)));
        discoveries.save(new InfluencerDiscovery(dupInf.getId(), "runstat-kw", "sc-runstat-dup", run2Start.plusSeconds(1)));
        discoveries.save(new InfluencerDiscovery(newInf.getId(), "runstat-kw", "sc-runstat-new", run2Start.plusSeconds(1)));

        rawDiscovery.save(new RawDiscoveryPost(cDup.getId(), run1.getId(), RawSource.HIKER_HASHTAG,
                Map.of("k", "v"), run1Start.plusSeconds(1)));
        rawDiscovery.save(new RawDiscoveryPost(cDup.getId(), run2.getId(), RawSource.HIKER_HASHTAG,
                Map.of("k", "v"), run2Start.plusSeconds(1)));
        rawDiscovery.save(new RawDiscoveryPost(cNew.getId(), run2.getId(), RawSource.HIKER_HASHTAG,
                Map.of("k", "v"), run2Start.plusSeconds(1)));

        // 리포지토리 집계 — run2: 인플루언서 2명 중 1명은 run2 시작 전에 이미 발굴돼 있었다(중복)
        var stats = rawDiscovery.discoveryStats(java.util.List.of(run1.getId(), run2.getId()));
        var byRun = stats.stream().collect(java.util.stream.Collectors.toMap(
                s -> s.getRunId(), s -> s));
        org.assertj.core.api.Assertions.assertThat(byRun.get(run1.getId()).getInfluencers()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(byRun.get(run1.getId()).getKnownInfluencers()).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(byRun.get(run2.getId()).getInfluencers()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(byRun.get(run2.getId()).getKnownInfluencers()).isEqualTo(1);

        // 렌더 — 건수 자리에 "2명", 중복 배지에 인플루언서 기준 "중복 1"
        mvc.perform(get("/ui/fragments/runs")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2명")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("중복 1")));
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
    void 게시물_수집_타일의_인플루언서_기준은_수집을_수행한_계정_수다() throws Exception {
        // 콘텐츠 보유 여부가 아니라 "수집을 수행한 계정 수" 기준 — 피드가 0건인 릴스 전용
        // 계정도 프로필 스냅샷을 찍었으면 FEED 기준에 포함돼야 한다(콘텐츠 기준이면 헷갈림).
        long snapBefore = influencers.countByLastCollectedAtIsNotNull();
        long reelsBefore = influencers.countByLastReelsAtIsNotNull();

        Influencer inf = new Influencer("smoke-snapshot-basis-user");
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.setBeauty(true);
        inf.setLastCollectedAt(Instant.now());  // 프로필 스냅샷 완료 — 피드 콘텐츠는 0건
        inf.setLastReelsAt(Instant.now());      // 릴스 수집 완료 — 릴스 콘텐츠도 0건
        influencers.save(inf);

        assertThat(influencers.countByLastCollectedAtIsNotNull()).isEqualTo(snapBefore + 1);

        mvc.perform(get("/ui/fragments/status-tiles")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "프로필 스냅샷 " + (snapBefore + 1) + "명")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "릴스 수집 " + (reelsBefore + 1) + "명")));
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

    @Test
    void 대시보드_수집_대기열은_첫방문_재방문_구분_없이_단일_카드다() throws Exception {
        // 모든 방문이 동일(최근 게시물 1회 수집)하므로 첫 방문/재방문 구분이 없다 —
        // 9일 전 방문(재방문 주기 7일 경과)한 뷰티 인플루언서도 같은 "수집 대기"로 잡힌다.
        Influencer inf = new Influencer("smoke-collect-due-user");
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.setBeauty(true);  // 수집 대기열은 뷰티 계정만 잡는다
        Instant nineDaysAgo = Instant.now().minus(java.time.Duration.ofDays(9));
        inf.setFirstCollectedAt(nineDaysAgo);
        inf.setLastCollectedAt(nineDaysAgo);
        influencers.save(inf);

        mvc.perform(get("/ui/fragments/status-tiles")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("READY")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("프로필 수집 대기")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("BACKFILL"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("TRACK"))));
    }

    @Test
    void 잡_화면에_프로필_수집과_릴스_수집_버튼이_분리되어_있다() throws Exception {
        mvc.perform(get("/ui/jobs")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("게시물을 위한 프로필 수집")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("릴스 수집")));
    }

    @Test
    void 대시보드_수집_대기열에_릴스_대기가_따로_렌더된다() throws Exception {
        Influencer inf = new Influencer("smoke-reels-due-user");
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.setBeauty(true);   // 릴스 대기열도 뷰티 계정만
        influencers.save(inf);

        mvc.perform(get("/ui/fragments/status-tiles")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("REELS_READY")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("게시물을 위한 프로필 수집")));
    }

    @Test
    void 뷰티_회사_리스트업_뷰는_회사_계정만_보여준다() throws Exception {
        Influencer company = new Influencer("smoke-company-list-user");
        company.setStatus(InfluencerStatus.QUALIFIED);
        company.setBeauty(true);
        company.setBeautyCompany(true);
        influencers.save(company);
        Influencer inf = new Influencer("smoke-influencer-list-user");
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.setBeauty(true);
        inf.setBeautyCompany(false);
        influencers.save(inf);

        mvc.perform(get("/ui/influencers").param("company", "true")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("smoke-company-list-user")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("smoke-influencer-list-user"))));

        // 사이드바 모니터링에 뷰티 회사 메뉴가 노출되고, 인플루언서 링크는 명시 쿼리를 갖는다
        // (쿼리 없는 링크는 admin.js 쿼리 복원에 붙잡혀 회사 뷰에서 되돌아올 수 없다)
        mvc.perform(get("/ui/influencers")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("뷰티 회사")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/ui/influencers?company=false")));
    }

    @Test
    void 데일리_수집_대시보드가_오늘_기준_진행을_렌더한다() throws Exception {
        // 오늘 스냅샷을 마친 뷰티 인플루언서 1명 — 오늘 완료 카운트에 잡혀야 한다
        Influencer done = new Influencer("smoke-daily-done-user");
        done.setStatus(InfluencerStatus.QUALIFIED);
        done.setBeauty(true);
        done.setBeautyCompany(false);
        done.setLastCollectedAt(Instant.now());
        done.setLastReelsAt(Instant.now());
        influencers.save(done);
        // 아직 오늘 방문 전인 뷰티 인플루언서 — 잔여 카운트에 잡혀야 한다
        Influencer pending = new Influencer("smoke-daily-pending-user");
        pending.setStatus(InfluencerStatus.QUALIFIED);
        pending.setBeauty(true);
        pending.setBeautyCompany(false);
        influencers.save(pending);

        mvc.perform(get("/ui/daily")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("데일리 수집")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("피드 완료")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("릴스 완료")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("사이클 완주")))
                // 지표 기준은 인플루언서 — raw_profile 행 수 타일은 없어야 한다
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("팔로워 스냅샷"))));

        // 사이드바에서 진입 가능
        mvc.perform(get("/ui")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/ui/daily")));
    }

    @Test
    void 데일리_수집에_오늘_모은_게시물_피드_릴스_타일이_렌더된다() throws Exception {
        // 인플루언서 단위(완료/잔여) 타일에 더해, 오늘 처음 발견된 게시물 수를 대시보드처럼
        // 게시물/피드/릴스로 쪼개 보여준다 — 어제 발견분은 오늘 타일에 잡히지 않는다.
        Influencer inf = influencers.save(new Influencer("smoke-daily-posts-user"));
        Instant yesterday = Instant.now().minus(java.time.Duration.ofDays(1));
        contents.save(new Content("sc-dt-feed1", ContentType.FEED, "smoke-daily-posts-user",
                inf.getId(), Instant.parse("2026-07-01T00:00:00Z"), Instant.now(), ContentOrigin.ENUMERATION));
        contents.save(new Content("sc-dt-feed2", ContentType.FEED, "smoke-daily-posts-user",
                inf.getId(), Instant.parse("2026-07-01T00:00:00Z"), Instant.now(), ContentOrigin.ENUMERATION));
        contents.save(new Content("sc-dt-reel", ContentType.REELS, "smoke-daily-posts-user",
                inf.getId(), Instant.parse("2026-07-01T00:00:00Z"), Instant.now(), ContentOrigin.ENUMERATION));
        contents.save(new Content("sc-dt-old", ContentType.FEED, "smoke-daily-posts-user",
                inf.getId(), Instant.parse("2026-07-01T00:00:00Z"), yesterday, ContentOrigin.ENUMERATION));

        var d = statusService.daily();
        assertThat(d.newPostsToday()).isEqualTo(3);
        assertThat(d.newFeedToday()).isEqualTo(2);
        assertThat(d.newReelsToday()).isEqualTo(1);

        mvc.perform(get("/ui/daily")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("오늘 모은 게시물")));
    }

    @Test
    void 대시보드에_뷰티_판정_결과_그룹이_렌더된다() throws Exception {
        // beauty 잡이 가른 결과(인플루언서/회사/비뷰티/미판정)가 판정과 수집 사이 단계로 보여야 한다.
        Influencer beauty = new Influencer("smoke-beauty-true-user");
        beauty.setStatus(InfluencerStatus.QUALIFIED);
        beauty.setBeauty(true);
        beauty.setBeautyCompany(false);
        influencers.save(beauty);
        Influencer company = new Influencer("smoke-beauty-company-user");
        company.setStatus(InfluencerStatus.QUALIFIED);
        company.setBeauty(true);
        company.setBeautyCompany(true);
        influencers.save(company);
        Influencer notBeauty = new Influencer("smoke-beauty-false-user");
        notBeauty.setStatus(InfluencerStatus.QUALIFIED);
        notBeauty.setBeauty(false);
        influencers.save(notBeauty);

        mvc.perform(get("/ui/fragments/status-tiles")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("뷰티 판정")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BEAUTY")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BEAUTY_COMPANY")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("뷰티 회사")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("NOT_BEAUTY")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("미판정")));
    }
}
