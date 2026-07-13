package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentStatus;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawCommentRepository;
import com.celfit.crawler.crawling.application.port.out.RawMediaPageRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.application.port.out.UserMediaPageFetcher;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.RawComment;
import com.celfit.crawler.crawling.domain.RawMediaPage;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageRequest;

class CollectJobTest {

    static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    // 컷오프 계산 기준 시각들 — backfill=6개월(2026-01-14 이전 컷오프), track=7일(2026-07-07 이전 컷오프)
    static final Instant WITHIN_BACKFILL_ONLY = Instant.parse("2026-03-01T00:00:00Z"); // 백필엔 포함, 추적엔 제외
    static final Instant WITHIN_TRACK = Instant.parse("2026-07-10T00:00:00Z");          // 둘 다 포함
    static final Instant BEFORE_BACKFILL = Instant.parse("2025-01-01T00:00:00Z");       // 둘 다 제외

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    RawProfileRepository rawProfiles = mock(RawProfileRepository.class);
    RawMediaPageRepository rawMediaPages = mock(RawMediaPageRepository.class);
    ContentRepository contents = mock(ContentRepository.class);
    RawCommentRepository rawComments = mock(RawCommentRepository.class);
    ProfileSourceSelector profileSourceSelector = mock(ProfileSourceSelector.class);
    CommentSourceSelector commentSource = mock(CommentSourceSelector.class);
    CrawlExecutor executor = mock(CrawlExecutor.class);
    SettingsService settings = mock(SettingsService.class);
    JobProgress progress = mock(JobProgress.class);

    AtomicLong runIdSeq = new AtomicLong(100);
    AtomicLong contentIdSeq = new AtomicLong(1000);

    @SuppressWarnings("unchecked")
    void wireExecutorPassthrough() {
        when(executor.execute(any(), any(), any(), any(), any(), any(Supplier.class)))
                .thenAnswer(inv -> {
                    Supplier<ApifyResult> work = inv.getArgument(5);
                    ApifyResult r = work.get();
                    return new CrawlExecutor.Execution(runIdSeq.incrementAndGet(), r.items());
                });
    }

    void wireDefaultSettings() {
        when(settings.collectBatchLimit()).thenReturn(10);
        when(settings.backfillMonths()).thenReturn(6);
        when(settings.trackWindowDays()).thenReturn(7);
        when(settings.commentsPerPost()).thenReturn(50);
        when(settings.maxAttempts()).thenReturn(3);
    }

    void wireContentSavePassthrough() {
        when(contents.save(any())).thenAnswer(inv -> {
            Content c = inv.getArgument(0);
            if (c.getId() == null) c.setId(contentIdSeq.incrementAndGet());
            return c;
        });
    }

    /** 댓글 대상이 없거나 이번 테스트가 댓글 결과를 신경 쓰지 않을 때의 기본값 — 응답 없음(전부 bump 대상). */
    void wireNoComments() {
        CommentFetcher noop = mock(CommentFetcher.class);
        when(commentSource.currentSource()).thenReturn(RawSource.SELF_GQL);
        when(commentSource.current()).thenReturn(noop);
        when(noop.fetch(any(), anyInt(), any())).thenReturn(new CommentFetcher.CommentResult(1L, Map.of()));
    }

    void wireCommon() {
        wireDefaultSettings();
        wireExecutorPassthrough();
        wireContentSavePassthrough();
        wireNoComments();
    }

    CollectJob job(List<UserMediaPageFetcher> fetchers) {
        return new CollectJob(influencers, rawProfiles, rawMediaPages, contents, rawComments,
                fetchers, profileSourceSelector, commentSource, executor, settings, CLOCK, progress);
    }

    static Influencer influencer(Long id, String username, Instant firstCollectedAt, Instant lastCollectedAt) {
        Influencer inf = new Influencer(username);
        inf.setId(id);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.setFirstCollectedAt(firstCollectedAt);
        inf.setLastCollectedAt(lastCollectedAt);
        return inf;
    }

    /** followers·userId·username을 flat 키로 읽는 소스(APIFY_ACTOR)를 프로필 원형으로 사용. */
    static Map<String, Object> profileItem(String username, long followers, String userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", username);
        m.put("followersCount", followers);
        m.put("userId", userId);
        return m;
    }

    void wireProfile(String username, long followers, String userId) {
        when(profileSourceSelector.currentSource()).thenReturn(RawSource.APIFY_ACTOR);
        when(profileSourceSelector.fetchAndSupplement(eq(List.of(username)), eq(TriggerType.MANUAL)))
                .thenReturn(new CrawlExecutor.Execution(1L, List.of(profileItem(username, followers, userId))));
    }

    // ---- HIKER_GQL_MEDIAS 페이지 빌더 ----
    static Map<String, Object> gqlItem(String code, Instant takenAt, boolean pinned) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("1ltaken_at", takenAt.getEpochSecond());
        m.put("product_type", "carousel_container");
        m.put("timeline_pinned_user_ids", pinned ? List.of("1") : List.of());
        return m;
    }

    static Map<String, Object> gqlPage(List<Map<String, Object>> items, String cursor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("more_available", cursor != null);
        if (cursor != null) m.put("profile_grid_items_cursor", cursor);
        return m;
    }

    // ---- HIKER_V2_CLIPS 페이지 빌더 ----
    static Map<String, Object> clipsItem(String code, Instant takenAt) {
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("code", code);
        media.put("taken_at", takenAt.getEpochSecond());
        media.put("product_type", "clips");
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put("media", media);
        return wrap;
    }

    static Map<String, Object> clipsPage(List<Map<String, Object>> items, String nextPageId) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("response", resp);
        if (nextPageId != null) m.put("next_page_id", nextPageId);
        return m;
    }

    static Map<String, Object> emptyPage(RawSource source) {
        return switch (source) {
            case HIKER_GQL_MEDIAS -> gqlPage(List.of(), null);
            case HIKER_V2_CLIPS -> clipsPage(List.of(), null);
            default -> throw new IllegalArgumentException("지원하지 않는 소스: " + source);
        };
    }

    /** 지정된 소스별 페이지 시퀀스를 순서대로 돌려주는 fake. 페이지 소진 후 추가 호출은 즉시 실패시켜 과다 호출을 잡는다. */
    static class FakeMediaFetcher implements UserMediaPageFetcher {
        final RawSource source;
        final List<Map<String, Object>> pages;
        final List<String> userIdsSeen = new ArrayList<>();
        final List<String> cursorsSeen = new ArrayList<>();
        int calls = 0;

        FakeMediaFetcher(RawSource source, List<Map<String, Object>> pages) {
            this.source = source;
            this.pages = pages;
        }

        @Override
        public RawSource source() {
            return source;
        }

        @Override
        public Map<String, Object> fetchPage(String userId, String cursor) {
            userIdsSeen.add(userId);
            cursorsSeen.add(cursor);
            if (calls >= pages.size()) {
                throw new AssertionError(source + " fetchPage 과다 호출(예상보다 많음): call#" + (calls + 1));
            }
            return pages.get(calls++);
        }
    }

    // ---------------------------------------------------------------------
    // 1) 백필 대상이 추적 대상보다 먼저 선정된다
    // ---------------------------------------------------------------------
    @Test
    void 백필_대상이_추적_대상보다_먼저_선정된다() {
        wireCommon();
        when(settings.collectBatchLimit()).thenReturn(5);

        Influencer backfillInf = influencer(1L, "backfill_user", null, null);
        Influencer trackInf = influencer(2L, "track_user", NOW.minusSeconds(1), NOW.minusSeconds(1));
        when(influencers.findCollectTargets(PageRequest.of(0, 5)))
                .thenReturn(List.of(backfillInf, trackInf));

        wireProfile("backfill_user", 1000L, "U1");
        wireProfile("track_user", 1000L, "U2");

        RawSource srcA = RawSource.HIKER_GQL_MEDIAS;
        RawSource srcB = RawSource.HIKER_V2_CLIPS;
        FakeMediaFetcher fetcherA = new FakeMediaFetcher(srcA, List.of(emptyPage(srcA), emptyPage(srcA)));
        FakeMediaFetcher fetcherB = new FakeMediaFetcher(srcB, List.of(emptyPage(srcB), emptyPage(srcB)));

        job(List.of(fetcherA, fetcherB)).run(TriggerType.MANUAL);

        verify(influencers).findCollectTargets(PageRequest.of(0, 5));
        InOrder order = inOrder(profileSourceSelector);
        order.verify(profileSourceSelector).fetchAndSupplement(eq(List.of("backfill_user")), eq(TriggerType.MANUAL));
        order.verify(profileSourceSelector).fetchAndSupplement(eq(List.of("track_user")), eq(TriggerType.MANUAL));
    }

    // ---------------------------------------------------------------------
    // 2) 방문 시 프로필 원형 저장 + followers 갱신 (userId 추출해 열거에 사용)
    // ---------------------------------------------------------------------
    @Test
    void 방문시_프로필_원형_저장과_followers_갱신_및_userId가_열거에_전달된다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        when(influencers.findCollectTargets(any())).thenReturn(List.of(inf));

        when(profileSourceSelector.currentSource()).thenReturn(RawSource.APIFY_ACTOR);
        Map<String, Object> profilePayload = profileItem("alice", 12345L, "USR1");
        when(profileSourceSelector.fetchAndSupplement(eq(List.of("alice")), eq(TriggerType.MANUAL)))
                .thenReturn(new CrawlExecutor.Execution(77L, List.of(profilePayload)));

        RawSource srcA = RawSource.HIKER_GQL_MEDIAS;
        RawSource srcB = RawSource.HIKER_V2_CLIPS;
        FakeMediaFetcher fetcherA = new FakeMediaFetcher(srcA, List.of(emptyPage(srcA)));
        FakeMediaFetcher fetcherB = new FakeMediaFetcher(srcB, List.of(emptyPage(srcB)));

        job(List.of(fetcherA, fetcherB)).run(TriggerType.MANUAL);

        ArgumentCaptor<RawProfile> captor = ArgumentCaptor.forClass(RawProfile.class);
        verify(rawProfiles).save(captor.capture());
        RawProfile rp = captor.getValue();
        assertThat(rp.getInfluencerId()).isEqualTo(1L);
        assertThat(rp.getCrawlRunId()).isEqualTo(77L);
        assertThat(rp.getSource()).isEqualTo(RawSource.APIFY_ACTOR);
        assertThat(rp.getPayload()).isEqualTo(profilePayload);
        assertThat(rp.getFollowers()).isEqualTo(12345L);
        assertThat(rp.getCapturedAt()).isEqualTo(NOW);
        assertThat(inf.getFollowers()).isEqualTo(12345L);

        assertThat(fetcherA.userIdsSeen).containsExactly("USR1");
        assertThat(fetcherB.userIdsSeen).containsExactly("USR1");
    }

    @Test
    void userId_추출_실패시_ApifyException으로_방문이_실패한다() {
        wireCommon();

        Influencer inf = influencer(1L, "bob", null, null);
        when(influencers.findCollectTargets(any())).thenReturn(List.of(inf));

        when(profileSourceSelector.currentSource()).thenReturn(RawSource.APIFY_ACTOR);
        Map<String, Object> noUserId = new LinkedHashMap<>();
        noUserId.put("username", "bob");
        noUserId.put("followersCount", 500L); // userId 없음
        when(profileSourceSelector.fetchAndSupplement(eq(List.of("bob")), eq(TriggerType.MANUAL)))
                .thenReturn(new CrawlExecutor.Execution(1L, List.of(noUserId)));

        var summary = job(List.of()).run(TriggerType.MANUAL);

        assertThat(summary.failedVisits()).isEqualTo(1);
        assertThat(summary.visited()).isEqualTo(0);
        assertThat(inf.getLastCollectedAt()).isNull();     // 방문 시각 미갱신
        assertThat(inf.getFirstCollectedAt()).isNull();
    }

    // ---------------------------------------------------------------------
    // 3) 두 스트림 페이지가 각각 raw_media_page에 source와 함께 저장된다
    // ---------------------------------------------------------------------
    @Test
    void 두_스트림_페이지가_각각_raw_media_page에_source와_함께_저장된다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        when(influencers.findCollectTargets(any())).thenReturn(List.of(inf));
        wireProfile("alice", 1000L, "USR1");

        RawSource srcA = RawSource.HIKER_GQL_MEDIAS;
        RawSource srcB = RawSource.HIKER_V2_CLIPS;
        Map<String, Object> pageA = gqlPage(List.of(gqlItem("A1", WITHIN_TRACK, false)), null);
        Map<String, Object> pageB = clipsPage(List.of(clipsItem("B1", WITHIN_TRACK)), null);
        FakeMediaFetcher fetcherA = new FakeMediaFetcher(srcA, List.of(pageA));
        FakeMediaFetcher fetcherB = new FakeMediaFetcher(srcB, List.of(pageB));

        job(List.of(fetcherA, fetcherB)).run(TriggerType.MANUAL);

        ArgumentCaptor<RawMediaPage> captor = ArgumentCaptor.forClass(RawMediaPage.class);
        verify(rawMediaPages, times(2)).save(captor.capture());
        List<RawMediaPage> saved = captor.getAllValues();
        assertThat(saved).extracting(RawMediaPage::getSource).containsExactlyInAnyOrder(srcA, srcB);
        assertThat(saved).extracting(RawMediaPage::getInfluencerId).containsOnly(1L);
        assertThat(saved).allSatisfy(p -> assertThat(p.getCapturedAt()).isEqualTo(NOW));
        RawMediaPage savedA = saved.stream().filter(p -> p.getSource() == srcA).findFirst().orElseThrow();
        assertThat(savedA.getPayload()).isEqualTo(pageA);
    }

    // ---------------------------------------------------------------------
    // 4) 컷오프 넘긴 페이지에서 중단하되, 고정 게시물은 중단 판단에서 제외
    // ---------------------------------------------------------------------
    @Test
    void 컷오프_이전_비고정_아이템만_있으면_스트림을_중단한다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null); // backfill 방문
        when(influencers.findCollectTargets(any())).thenReturn(List.of(inf));
        wireProfile("alice", 1000L, "USR1");

        RawSource srcA = RawSource.HIKER_GQL_MEDIAS;
        // 커서를 달아 다음 페이지가 있는 것처럼 보이지만, 비고정 아이템이 전부 컷오프 이전이라 중단해야 한다.
        Map<String, Object> page1 = gqlPage(List.of(gqlItem("OLD1", BEFORE_BACKFILL, false)), "C2");
        FakeMediaFetcher fetcherA = new FakeMediaFetcher(srcA, List.of(page1)); // page2는 없음 — 호출되면 즉시 실패

        job(List.of(fetcherA)).run(TriggerType.MANUAL);

        assertThat(fetcherA.calls).isEqualTo(1);
        verify(contents, never()).findByShortCode("OLD1");
    }

    @Test
    void 고정_게시물은_컷오프_중단_판단에서_제외된다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null); // backfill 방문
        when(influencers.findCollectTargets(any())).thenReturn(List.of(inf));
        wireProfile("alice", 1000L, "USR1");

        RawSource srcA = RawSource.HIKER_GQL_MEDIAS;
        // page1: 고정된 오래된 아이템 하나뿐(비고정 없음) → 중단 판단 대상이 없어 계속 진행해야 한다.
        Map<String, Object> page1 = gqlPage(List.of(gqlItem("PIN_OLD", BEFORE_BACKFILL, true)), "C2");
        Map<String, Object> page2 = gqlPage(List.of(), null); // 자연 종료
        FakeMediaFetcher fetcherA = new FakeMediaFetcher(srcA, List.of(page1, page2));

        job(List.of(fetcherA)).run(TriggerType.MANUAL);

        assertThat(fetcherA.calls).isEqualTo(2); // page2까지 호출됨 = 고정 아이템 때문에 중단하지 않았다
        assertThat(fetcherA.cursorsSeen).containsExactly(null, "C2");
    }

    // ---------------------------------------------------------------------
    // 5) 두 스트림 shortCode 중복은 content 1건 (윈도우 안이면 고정이라도 포함)
    // ---------------------------------------------------------------------
    @Test
    void 두_스트림_shortCode_중복은_content_1건이고_윈도우_안_고정게시물도_포함된다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        when(influencers.findCollectTargets(any())).thenReturn(List.of(inf));
        wireProfile("alice", 1000L, "USR1");

        RawSource srcA = RawSource.HIKER_GQL_MEDIAS;
        RawSource srcB = RawSource.HIKER_V2_CLIPS;
        Map<String, Object> pageA = gqlPage(List.of(
                gqlItem("DUP1", WITHIN_TRACK, false),
                gqlItem("PIN_IN", WITHIN_TRACK, true)), null);
        Map<String, Object> pageB = clipsPage(List.of(clipsItem("DUP1", WITHIN_TRACK)), null);
        FakeMediaFetcher fetcherA = new FakeMediaFetcher(srcA, List.of(pageA));
        FakeMediaFetcher fetcherB = new FakeMediaFetcher(srcB, List.of(pageB));

        var summary = job(List.of(fetcherA, fetcherB)).run(TriggerType.MANUAL);

        ArgumentCaptor<Content> captor = ArgumentCaptor.forClass(Content.class);
        verify(contents, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Content::getShortCode)
                .containsExactlyInAnyOrder("DUP1", "PIN_IN");
        assertThat(summary.postsUpserted()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // 6) 댓글 페이지가 content별 raw_comment에 원형 저장되고 content가 COLLECTED로 전이
    // ---------------------------------------------------------------------
    @Test
    void 댓글_페이지가_content별_raw_comment에_저장되고_COLLECTED로_전이한다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        when(influencers.findCollectTargets(any())).thenReturn(List.of(inf));
        wireProfile("alice", 1000L, "USR1");

        RawSource srcA = RawSource.HIKER_GQL_MEDIAS;
        Map<String, Object> page = gqlPage(List.of(gqlItem("POST1", WITHIN_TRACK, false)), null);
        FakeMediaFetcher fetcherA = new FakeMediaFetcher(srcA, List.of(page));

        Map<String, Object> commentPage1 = Map.of("page", 1, "comments", List.of("c1"));
        Map<String, Object> commentPage2 = Map.of("page", 2, "comments", List.of("c2"));
        CommentFetcher fakeCommentFetcher = mock(CommentFetcher.class);
        when(commentSource.currentSource()).thenReturn(RawSource.SELF_GQL);
        when(commentSource.current()).thenReturn(fakeCommentFetcher);
        when(fakeCommentFetcher.fetch(eq(List.of("POST1")), eq(50), eq(TriggerType.MANUAL)))
                .thenReturn(new CommentFetcher.CommentResult(55L,
                        Map.of("POST1", List.of(commentPage1, commentPage2))));

        var summary = job(List.of(fetcherA)).run(TriggerType.MANUAL);

        ArgumentCaptor<RawComment> captor = ArgumentCaptor.forClass(RawComment.class);
        verify(rawComments, times(2)).save(captor.capture());
        List<RawComment> saved = captor.getAllValues();
        assertThat(saved).allSatisfy(c -> {
            assertThat(c.getCrawlRunId()).isEqualTo(55L);
            assertThat(c.getSource()).isEqualTo(RawSource.SELF_GQL);
            assertThat(c.getCapturedAt()).isEqualTo(NOW);
        });
        assertThat(saved).extracting(RawComment::getPayload).containsExactlyInAnyOrder(commentPage1, commentPage2);

        ArgumentCaptor<Content> contentCaptor = ArgumentCaptor.forClass(Content.class);
        verify(contents).save(contentCaptor.capture());
        Content savedContent = contentCaptor.getValue();
        assertThat(savedContent.getStatus()).isEqualTo(ContentStatus.COLLECTED);
        assertThat(savedContent.getCollectedAt()).isEqualTo(NOW);
        assertThat(summary.postsCollected()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // 7) 댓글 실패 시 collect_attempts 증가, 상한 도달 시 FAILED
    // ---------------------------------------------------------------------
    @Test
    void 댓글이_없는_content는_시도가_증가하고_상한_도달시_FAILED된다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        when(influencers.findCollectTargets(any())).thenReturn(List.of(inf));
        wireProfile("alice", 1000L, "USR1");

        RawSource srcA = RawSource.HIKER_GQL_MEDIAS;
        Map<String, Object> page = gqlPage(List.of(
                gqlItem("POST_OK", WITHIN_TRACK, false),
                gqlItem("POST_FAIL", WITHIN_TRACK, false)), null);
        FakeMediaFetcher fetcherA = new FakeMediaFetcher(srcA, List.of(page));

        // POST_FAIL은 이미 시도 2회(상한 3) — 이번 실패로 3회째가 되어 FAILED 전이돼야 한다.
        Content existingFail = new Content("POST_FAIL", ContentType.FEED, "alice", 1L,
                WITHIN_TRACK, NOW.minusSeconds(10));
        existingFail.setId(2000L);
        existingFail.setCollectAttempts(2);
        when(contents.findByShortCode("POST_FAIL")).thenReturn(Optional.of(existingFail));

        CommentFetcher fakeCommentFetcher = mock(CommentFetcher.class);
        when(commentSource.currentSource()).thenReturn(RawSource.SELF_GQL);
        when(commentSource.current()).thenReturn(fakeCommentFetcher);
        when(fakeCommentFetcher.fetch(any(), eq(50), eq(TriggerType.MANUAL)))
                .thenReturn(new CommentFetcher.CommentResult(55L,
                        Map.of("POST_OK", List.of(Map.of("p", 1))))); // POST_FAIL은 응답에 없음

        job(List.of(fetcherA)).run(TriggerType.MANUAL);

        assertThat(existingFail.getCollectAttempts()).isEqualTo(3);
        assertThat(existingFail.getStatus()).isEqualTo(ContentStatus.FAILED);
    }

    @Test
    void 댓글_수집_자체가_실패하면_모든_pending_content의_시도가_증가한다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        when(influencers.findCollectTargets(any())).thenReturn(List.of(inf));
        wireProfile("alice", 1000L, "USR1");

        RawSource srcA = RawSource.HIKER_GQL_MEDIAS;
        Map<String, Object> page = gqlPage(List.of(gqlItem("POST1", WITHIN_TRACK, false)), null);
        FakeMediaFetcher fetcherA = new FakeMediaFetcher(srcA, List.of(page));

        CommentFetcher fakeCommentFetcher = mock(CommentFetcher.class);
        when(commentSource.currentSource()).thenReturn(RawSource.SELF_GQL);
        when(commentSource.current()).thenReturn(fakeCommentFetcher);
        when(fakeCommentFetcher.fetch(any(), eq(50), eq(TriggerType.MANUAL)))
                .thenThrow(new ApifyException("차단됨"));

        var summary = job(List.of(fetcherA)).run(TriggerType.MANUAL);

        ArgumentCaptor<Content> captor = ArgumentCaptor.forClass(Content.class);
        verify(contents).save(captor.capture());
        assertThat(captor.getValue().getCollectAttempts()).isEqualTo(1);
        assertThat(captor.getValue().getStatus()).isEqualTo(ContentStatus.PENDING); // 아직 상한 미도달
        assertThat(summary.postsCollected()).isEqualTo(0);
        verify(rawComments, never()).save(any());
    }

    // ---------------------------------------------------------------------
    // 8) 방문 완료 시 first_collected_at(첫 방문만)·last_collected_at 갱신
    // ---------------------------------------------------------------------
    @Test
    void 첫_방문이면_first_last_collected_at_모두_갱신된다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        when(influencers.findCollectTargets(any())).thenReturn(List.of(inf));
        wireProfile("alice", 1000L, "USR1");

        RawSource srcA = RawSource.HIKER_GQL_MEDIAS;
        FakeMediaFetcher fetcherA = new FakeMediaFetcher(srcA, List.of(emptyPage(srcA)));

        job(List.of(fetcherA)).run(TriggerType.MANUAL);

        assertThat(inf.getFirstCollectedAt()).isEqualTo(NOW);
        assertThat(inf.getLastCollectedAt()).isEqualTo(NOW);
    }

    @Test
    void 추적_방문이면_first_collected_at은_유지되고_last_collected_at만_갱신된다() {
        wireCommon();

        Instant original = NOW.minus(Duration.ofDays(30));
        Influencer inf = influencer(1L, "alice", original, original);
        when(influencers.findCollectTargets(any())).thenReturn(List.of(inf));
        wireProfile("alice", 1000L, "USR1");

        RawSource srcA = RawSource.HIKER_GQL_MEDIAS;
        FakeMediaFetcher fetcherA = new FakeMediaFetcher(srcA, List.of(emptyPage(srcA)));

        job(List.of(fetcherA)).run(TriggerType.MANUAL);

        assertThat(inf.getFirstCollectedAt()).isEqualTo(original); // 변경 없음
        assertThat(inf.getLastCollectedAt()).isEqualTo(NOW);
    }

    // ---------------------------------------------------------------------
    // 9) 추적 방문(첫 방문 완료된 인플루언서)은 track-window-days 컷오프를 쓴다
    // ---------------------------------------------------------------------
    @Test
    void 추적_방문은_backfill보다_좁은_track_window_컷오프를_쓴다() {
        wireCommon();

        Influencer backfillInf = influencer(1L, "backfill_user", null, null);
        Influencer trackInf = influencer(2L, "track_user", NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30)));
        when(influencers.findCollectTargets(any())).thenReturn(List.of(backfillInf, trackInf));
        wireProfile("backfill_user", 1000L, "U1");
        wireProfile("track_user", 1000L, "U2");

        RawSource srcA = RawSource.HIKER_GQL_MEDIAS;
        // 두 인플루언서 모두 동일 시점(WITHIN_BACKFILL_ONLY: backfill엔 포함, track엔 제외)의 아이템 1건.
        Map<String, Object> page1 = gqlPage(List.of(gqlItem("OLD_TRACK_1", WITHIN_BACKFILL_ONLY, false)), null);
        Map<String, Object> page2 = gqlPage(List.of(gqlItem("OLD_TRACK_2", WITHIN_BACKFILL_ONLY, false)), null);
        FakeMediaFetcher fetcherA = new FakeMediaFetcher(srcA, List.of(page1, page2));

        job(List.of(fetcherA)).run(TriggerType.MANUAL);

        verify(contents).findByShortCode("OLD_TRACK_1");         // 백필 대상은 윈도우 안 → upsert 시도
        verify(contents, never()).findByShortCode("OLD_TRACK_2"); // 추적 대상은 track-window 밖 → upsert 시도 없음
    }
}
