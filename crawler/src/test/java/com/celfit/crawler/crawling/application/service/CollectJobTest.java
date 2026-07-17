package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentOrigin;
import com.celfit.crawler.content.domain.ContentStatus;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawCommentRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.common.config.CollectProperties;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawComment;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * collect = 게시물을 위한 프로필 수집. 피드는 프로필 원형 내장분만 쓰고(별도 요청 없음),
 * 프로필 갱신 실패는 유료 폴백 없이 방문 실패(재시도)다 — 미디어 페처 의존 자체가 없다.
 */
class CollectJobTest {

    static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    // 기간 컷오프가 없으므로 날짜는 임의값 — OLD는 "아주 오래된 게시물도 수집됨" 검증용
    static final Instant RECENT = Instant.parse("2026-07-10T00:00:00Z");
    static final Instant OLD = Instant.parse("2025-01-01T00:00:00Z");

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    RawProfileRepository rawProfiles = mock(RawProfileRepository.class);
    ContentRepository contents = mock(ContentRepository.class);
    RawCommentRepository rawComments = mock(RawCommentRepository.class);
    ProfileSourceSelector profileSourceSelector = mock(ProfileSourceSelector.class);
    CommentSourceSelector commentSource = mock(CommentSourceSelector.class);
    CrawlExecutor executor = mock(CrawlExecutor.class);
    SettingsService settings = mock(SettingsService.class);
    JobProgress progress = mock(JobProgress.class);
    // 실객체 주입 — execute()가 콜백을 즉시 실행하므로 방문 단위 트랜잭션 래핑을 그대로 재현한다.
    TransactionTemplate txTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));

    AtomicLong runIdSeq = new AtomicLong(100);
    AtomicLong contentIdSeq = new AtomicLong(1000);
    // findByShortCode/save/findByInfluencerIdAndStatus를 일관되게 뒷받침하는 인메모리 저장소.
    Map<String, Content> contentStore = new LinkedHashMap<>();

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
        when(settings.commentsPerPost()).thenReturn(50);
        when(settings.maxAttempts()).thenReturn(3);
        when(settings.revisitIntervalDays()).thenReturn(7);
    }

    /**
     * contents mock을 contentStore 기반 페이크로 만든다 — findByShortCode/save/
     * findByInfluencerIdAndStatusAndOrigin이 같은 저장소를 공유해야 "댓글 대상 = 방문 윈도우가 아니라
     * ENUMERATION PENDING 전체"(Finding 1 + origin 도입) 리팩터를 검증할 수 있다.
     */
    void wireContentSavePassthrough() {
        when(contents.save(any())).thenAnswer(inv -> {
            Content c = inv.getArgument(0);
            if (c.getId() == null) c.setId(contentIdSeq.incrementAndGet());
            contentStore.put(c.getShortCode(), c);
            return c;
        });
        when(contents.findByShortCode(any())).thenAnswer(inv ->
                Optional.ofNullable(contentStore.get((String) inv.getArgument(0))));
        when(contents.saveAll(any())).thenAnswer(inv -> {
            java.util.List<Content> batch = new java.util.ArrayList<>();
            for (Content c : (Iterable<Content>) inv.getArgument(0)) {
                if (c.getId() == null) c.setId(contentIdSeq.incrementAndGet());
                contentStore.put(c.getShortCode(), c);
                batch.add(c);
            }
            return batch;
        });
        when(contents.findByShortCodeIn(any())).thenAnswer(inv -> {
            java.util.Collection<String> codes = inv.getArgument(0);
            return codes.stream().map(contentStore::get).filter(java.util.Objects::nonNull).toList();
        });
        when(contents.findByInfluencerIdAndStatusAndOrigin(anyLong(), any(), any())).thenAnswer(inv -> {
            Long infId = inv.getArgument(0);
            ContentStatus status = inv.getArgument(1);
            ContentOrigin origin = inv.getArgument(2);
            return contentStore.values().stream()
                    .filter(c -> c.getInfluencerId().equals(infId) && c.getStatus() == status
                            && c.getOrigin() == origin)
                    .toList();
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

    CollectJob job() {
        return job(true);   // 대부분의 테스트는 댓글 로직 회귀 검증을 위해 켬
    }

    CollectJob job(boolean commentsEnabled) {
        CollectProperties props = new CollectProperties(10, 50, 3, 7, commentsEnabled);
        return new CollectJob(props, influencers, rawProfiles, contents,
                new ContentUpserter(contents, CLOCK), rawComments,
                profileSourceSelector, commentSource, executor, settings, CLOCK, progress,
                txTemplate);
    }

    @Test
    void 방문을_여러_워커가_병렬로_처리한다() {
        // 방문은 계정 단위로 독립(트랜잭션·북키핑 분리)이라 qualify의 SELF fetch처럼 워커 병렬이 안전하다.
        // 순차 실행이면 동시 진행 fetch가 항상 1에 머물러 실패한다.
        wireCommon();
        List<Influencer> targets = new java.util.ArrayList<>();
        for (long i = 1; i <= 8; i++) targets.add(influencer(i, "par_user" + i, null, null));
        when(influencers.findCollectTargets(any(), any())).thenReturn(targets);
        when(profileSourceSelector.currentSource()).thenReturn(RawSource.APIFY_ACTOR);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var maxActive = new java.util.concurrent.atomic.AtomicInteger();
        when(profileSourceSelector.fetchAndSupplement(eq(JobName.COLLECT), any(), eq(TriggerType.MANUAL)))
                .thenAnswer(inv -> {
                    maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                    Thread.sleep(100);   // 겹침 관찰 창
                    active.decrementAndGet();
                    List<String> names = inv.getArgument(1);
                    return new CrawlExecutor.Execution(1L,
                            List.of(profileItem(names.get(0), 1000L, "U-" + names.get(0))));
                });

        var summary = job().run(TriggerType.MANUAL);

        assertThat(summary.visited()).isEqualTo(8);
        assertThat(maxActive.get()).isGreaterThanOrEqualTo(3);  // 워커 4개 병렬 실행의 증거
        assertThat(targets).allSatisfy(t -> assertThat(t.getLastCollectedAt()).isEqualTo(NOW));
    }

    @Test
    void 프로필_404_계정은_방문_실패가_아니라_소프트_딜리트된다() {
        wireCommon();
        Influencer gone = influencer(1L, "gone", null, null);
        when(influencers.findCollectTargets(any(), any(PageRequest.class))).thenReturn(List.of(gone));
        when(profileSourceSelector.currentSource()).thenReturn(RawSource.SELF_GQL);
        when(profileSourceSelector.fetchAndSupplement(eq(JobName.COLLECT), eq(List.of("gone")), eq(TriggerType.MANUAL)))
                .thenReturn(new CrawlExecutor.Execution(1L, List.of(), List.of("gone")));

        var s = job().run(TriggerType.MANUAL);

        assertThat(gone.getStatus()).isEqualTo(InfluencerStatus.DELETED);
        verify(influencers).save(gone);
        assertThat(s.failedVisits()).isZero();   // 재시도 무의미 — 실패로 세지 않는다
        assertThat(gone.getLastCollectedAt()).isNull();  // 방문 완료로도 치지 않는다
    }

    static Influencer influencer(Long id, String username, Instant firstCollectedAt, Instant lastCollectedAt) {
        Influencer inf = new Influencer(username);
        inf.setId(id);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.setFirstCollectedAt(firstCollectedAt);
        inf.setLastCollectedAt(lastCollectedAt);
        return inf;
    }

    /** followers·userId·username을 flat 키로 읽는 소스(APIFY_ACTOR)를 프로필 원형으로 사용 — 내장 타임라인 없음. */
    static Map<String, Object> profileItem(String username, long followers, String userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", username);
        m.put("followersCount", followers);
        m.put("userId", userId);
        return m;
    }

    void wireProfile(String username, long followers, String userId) {
        when(profileSourceSelector.currentSource()).thenReturn(RawSource.APIFY_ACTOR);
        when(profileSourceSelector.fetchAndSupplement(eq(JobName.COLLECT), eq(List.of(username)), eq(TriggerType.MANUAL)))
                .thenReturn(new CrawlExecutor.Execution(1L, List.of(profileItem(username, followers, userId))));
    }

    // ---- SELF_GQL 프로필(web_profile_info) 원형 — 최근 피드 12개 내장 ----
    static Map<String, Object> selfTimelineNode(String shortCode, Instant takenAt,
                                                String productType, boolean pinned) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("shortcode", shortCode);
        node.put("taken_at_timestamp", takenAt.getEpochSecond());
        node.put("product_type", productType);
        node.put("pinned_for_users", pinned ? List.of(Map.of("id", "1")) : List.of());
        return node;
    }

    static Map<String, Object> selfPayload(String username, long followers, String userId,
                                           List<Map<String, Object>> timelineNodes) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        user.put("id", userId);
        user.put("edge_followed_by", Map.of("count", followers));
        user.put("edge_owner_to_timeline_media", Map.of(
                "edges", timelineNodes.stream().map(n -> (Object) Map.of("node", n)).toList()));
        return Map.of("data", Map.of("user", user));
    }

    void wireSelfProfile(String username, long followers, String userId,
                         List<Map<String, Object>> timelineNodes) {
        when(profileSourceSelector.currentSource()).thenReturn(RawSource.SELF_GQL);
        when(profileSourceSelector.fetchAndSupplement(eq(JobName.COLLECT), eq(List.of(username)), eq(TriggerType.MANUAL)))
                .thenReturn(new CrawlExecutor.Execution(1L,
                        List.of(selfPayload(username, followers, userId, timelineNodes))));
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
        when(influencers.findCollectTargets(any(), eq(PageRequest.of(0, 5))))
                .thenReturn(List.of(backfillInf, trackInf));

        wireProfile("backfill_user", 1000L, "U1");
        wireProfile("track_user", 1000L, "U2");

        job().run(TriggerType.MANUAL);

        // 우선순위는 선정 쿼리 정렬(백필 먼저)이 담당하고, 병렬 방문은 리스트 앞에서부터 집어간다 —
        // 워커 간 완료 순서는 보장되지 않으므로 호출 순서(InOrder)가 아니라 "둘 다 방문됨"을 검증한다.
        verify(influencers).findCollectTargets(any(), eq(PageRequest.of(0, 5)));
        verify(profileSourceSelector).fetchAndSupplement(
                eq(JobName.COLLECT), eq(List.of("backfill_user")), eq(TriggerType.MANUAL));
        verify(profileSourceSelector).fetchAndSupplement(
                eq(JobName.COLLECT), eq(List.of("track_user")), eq(TriggerType.MANUAL));
    }

    // ---------------------------------------------------------------------
    // 1b) 대상 조회는 revisit-interval-days 만큼 과거 시각을 컷오프로 전달한다
    // ---------------------------------------------------------------------
    @Test
    void 대상_조회는_재방문_주기만큼_과거인_시각을_컷오프로_전달한다() {
        wireCommon();
        when(settings.revisitIntervalDays()).thenReturn(7);
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of());

        job().run(TriggerType.MANUAL);

        verify(influencers).findCollectTargets(eq(NOW.minus(Duration.ofDays(7))), any());
    }

    // ---------------------------------------------------------------------
    // 2) 방문 시 프로필 원형 저장 + followers·igUserId 갱신
    // ---------------------------------------------------------------------
    @Test
    void 방문시_프로필_원형_저장과_followers_igUserId가_갱신된다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));

        when(profileSourceSelector.currentSource()).thenReturn(RawSource.APIFY_ACTOR);
        Map<String, Object> profilePayload = profileItem("alice", 12345L, "USR1");
        when(profileSourceSelector.fetchAndSupplement(eq(JobName.COLLECT), eq(List.of("alice")), eq(TriggerType.MANUAL)))
                .thenReturn(new CrawlExecutor.Execution(77L, List.of(profilePayload)));

        job().run(TriggerType.MANUAL);

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
        assertThat(inf.getIgUserId()).isEqualTo("USR1");
    }

    @Test
    void userId_추출_실패시_ApifyException으로_방문이_실패한다() {
        wireCommon();

        Influencer inf = influencer(1L, "bob", null, null);
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));

        when(profileSourceSelector.currentSource()).thenReturn(RawSource.APIFY_ACTOR);
        Map<String, Object> noUserId = new LinkedHashMap<>();
        noUserId.put("username", "bob");
        noUserId.put("followersCount", 500L); // userId 없음
        when(profileSourceSelector.fetchAndSupplement(eq(JobName.COLLECT), eq(List.of("bob")), eq(TriggerType.MANUAL)))
                .thenReturn(new CrawlExecutor.Execution(1L, List.of(noUserId)));

        var summary = job().run(TriggerType.MANUAL);

        assertThat(summary.failedVisits()).isEqualTo(1);
        assertThat(summary.visited()).isEqualTo(0);
        assertThat(inf.getLastCollectedAt()).isNull();     // 방문 시각 미갱신
        assertThat(inf.getFirstCollectedAt()).isNull();
    }

    // ---------------------------------------------------------------------
    // 2b) 프로필 갱신 실패 = 방문 실패 — 프로필이 방문의 유일한 재료라 유료 폴백 없이
    //     재시도로 남긴다 (팔로워 추이 스냅샷 구멍 방지)
    // ---------------------------------------------------------------------
    @Test
    void 프로필_응답에_계정이_없으면_저장된_igUserId가_있어도_방문이_실패하고_재시도된다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        inf.setIgUserId("STORED1");
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        when(profileSourceSelector.currentSource()).thenReturn(RawSource.APIFY_ACTOR);
        when(profileSourceSelector.fetchAndSupplement(eq(JobName.COLLECT), eq(List.of("alice")), eq(TriggerType.MANUAL)))
                .thenReturn(new CrawlExecutor.Execution(1L, List.of())); // 401 등으로 계정이 응답에 없음

        var summary = job().run(TriggerType.MANUAL);

        assertThat(summary.failedVisits()).isEqualTo(1);
        assertThat(summary.visited()).isZero();
        assertThat(inf.getLastCollectedAt()).isNull();   // 북키핑 미갱신 — 다음 실행 재선정
        verify(rawProfiles, never()).save(any());
    }

    @Test
    void 프로필_요청_예외시에도_방문이_실패하고_재시도된다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        inf.setIgUserId("STORED1");
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        when(profileSourceSelector.currentSource()).thenReturn(RawSource.APIFY_ACTOR);
        when(profileSourceSelector.fetchAndSupplement(eq(JobName.COLLECT), eq(List.of("alice")), eq(TriggerType.MANUAL)))
                .thenThrow(new ApifyException("프로필 요청 실패"));

        var summary = job().run(TriggerType.MANUAL);

        assertThat(summary.failedVisits()).isEqualTo(1);
        assertThat(summary.visited()).isZero();
        assertThat(inf.getLastCollectedAt()).isNull();
    }

    // ---------------------------------------------------------------------
    // 3) 피드는 프로필 내장 타임라인만 — 별도 요청 없음
    // ---------------------------------------------------------------------
    @Test
    void 프로필_내장_타임라인의_피드와_릴스_유형이_그대로_수집된다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        wireSelfProfile("alice", 12345L, "USR1", List.of(
                selfTimelineNode("EMB_FEED", RECENT, "", false),
                selfTimelineNode("EMB_REEL", OLD, "clips", true)));

        var summary = job().run(TriggerType.MANUAL);

        assertThat(summary.postsUpserted()).isEqualTo(2);          // 날짜·고정 무관 전부 수집
        assertThat(contentStore.get("EMB_FEED").getContentType()).isEqualTo(ContentType.FEED);
        assertThat(contentStore.get("EMB_REEL").getContentType()).isEqualTo(ContentType.REELS);
    }

    @Test
    void 프로필에_내장_타임라인이_없으면_추가_요청_없이_프로필만_갱신한다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        wireProfile("alice", 1000L, "USR1"); // APIFY_ACTOR 형태 — 내장 타임라인 없음

        var summary = job().run(TriggerType.MANUAL);

        assertThat(summary.visited()).isEqualTo(1);
        assertThat(summary.postsUpserted()).isZero();              // 피드 폴백 없음
        verify(rawProfiles).save(any());                           // 프로필 스냅샷은 저장
        assertThat(inf.getLastCollectedAt()).isEqualTo(NOW);
    }

    @Test
    void 기간_컷오프_없이_오래된_게시물도_수집된다() {
        wireCommon();

        // 재방문(추적)이어도 컷오프가 없다 — 내장 타임라인에 있으면 날짜 무관 전부 수집
        Influencer inf = influencer(1L, "alice", NOW.minus(Duration.ofDays(60)), NOW.minus(Duration.ofDays(60)));
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        wireSelfProfile("alice", 1000L, "USR1", List.of(
                selfTimelineNode("VERY_OLD", OLD, "", false)));

        var summary = job().run(TriggerType.MANUAL);

        verify(contents).findByShortCodeIn(argThat(codes -> codes.contains("VERY_OLD")));
        assertThat(summary.postsUpserted()).isEqualTo(1);
    }

    @Test
    void 윈도우_안_고정게시물도_포함된다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        wireSelfProfile("alice", 1000L, "USR1", List.of(
                selfTimelineNode("NORM1", RECENT, "", false),
                selfTimelineNode("PIN_IN", RECENT, "", true)));

        var summary = job().run(TriggerType.MANUAL);

        // 일괄 upsert 전환 — 개별 save 대신 contentStore 최종 상태로 검증
        assertThat(contentStore).containsKeys("NORM1", "PIN_IN");
        assertThat(summary.postsUpserted()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // 6) 댓글 페이지가 content별 raw_comment에 원형 저장되고 content가 COLLECTED로 전이
    // ---------------------------------------------------------------------
    @Test
    void 댓글_페이지가_content별_raw_comment에_저장되고_COLLECTED로_전이한다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        wireSelfProfile("alice", 1000L, "USR1", List.of(
                selfTimelineNode("POST1", RECENT, "", false)));

        Map<String, Object> commentPage1 = Map.of("page", 1, "comments", List.of("c1"));
        Map<String, Object> commentPage2 = Map.of("page", 2, "comments", List.of("c2"));
        CommentFetcher fakeCommentFetcher = mock(CommentFetcher.class);
        when(commentSource.currentSource()).thenReturn(RawSource.SELF_GQL);
        when(commentSource.current()).thenReturn(fakeCommentFetcher);
        when(fakeCommentFetcher.fetch(eq(List.of("POST1")), eq(50), eq(TriggerType.MANUAL)))
                .thenReturn(new CommentFetcher.CommentResult(55L,
                        Map.of("POST1", List.of(commentPage1, commentPage2))));

        var summary = job().run(TriggerType.MANUAL);

        ArgumentCaptor<RawComment> captor = ArgumentCaptor.forClass(RawComment.class);
        verify(rawComments, times(2)).save(captor.capture());
        List<RawComment> saved = captor.getAllValues();
        assertThat(saved).allSatisfy(c -> {
            assertThat(c.getCrawlRunId()).isEqualTo(55L);
            assertThat(c.getSource()).isEqualTo(RawSource.SELF_GQL);
            assertThat(c.getCapturedAt()).isEqualTo(NOW);
        });
        assertThat(saved).extracting(RawComment::getPayload).containsExactlyInAnyOrder(commentPage1, commentPage2);

        Content savedContent = contentStore.get("POST1");
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
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        wireSelfProfile("alice", 1000L, "USR1", List.of(
                selfTimelineNode("POST_OK", RECENT, "", false),
                selfTimelineNode("POST_FAIL", RECENT, "", false)));

        // POST_FAIL은 이미 시도 2회(상한 3) — 이번 실패로 3회째가 되어 FAILED 전이돼야 한다.
        Content existingFail = new Content("POST_FAIL", ContentType.FEED, "alice", 1L,
                RECENT, NOW.minusSeconds(10), ContentOrigin.ENUMERATION);
        existingFail.setId(2000L);
        existingFail.setCollectAttempts(2);
        contentStore.put("POST_FAIL", existingFail);

        CommentFetcher fakeCommentFetcher = mock(CommentFetcher.class);
        when(commentSource.currentSource()).thenReturn(RawSource.SELF_GQL);
        when(commentSource.current()).thenReturn(fakeCommentFetcher);
        when(fakeCommentFetcher.fetch(any(), eq(50), eq(TriggerType.MANUAL)))
                .thenReturn(new CommentFetcher.CommentResult(55L,
                        Map.of("POST_OK", List.of(Map.of("p", 1))))); // POST_FAIL은 응답에 없음

        job().run(TriggerType.MANUAL);

        assertThat(existingFail.getCollectAttempts()).isEqualTo(3);
        assertThat(existingFail.getStatus()).isEqualTo(ContentStatus.FAILED);
    }

    @Test
    void 댓글_수집_자체가_실패하면_모든_pending_content의_시도가_증가한다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        wireSelfProfile("alice", 1000L, "USR1", List.of(
                selfTimelineNode("POST1", RECENT, "", false)));

        CommentFetcher fakeCommentFetcher = mock(CommentFetcher.class);
        when(commentSource.currentSource()).thenReturn(RawSource.SELF_GQL);
        when(commentSource.current()).thenReturn(fakeCommentFetcher);
        when(fakeCommentFetcher.fetch(any(), eq(50), eq(TriggerType.MANUAL)))
                .thenThrow(new ApifyException("차단됨"));

        var summary = job().run(TriggerType.MANUAL);

        Content saved = contentStore.get("POST1");
        assertThat(saved.getCollectAttempts()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(ContentStatus.PENDING); // 아직 상한 미도달
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
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        wireProfile("alice", 1000L, "USR1");

        job().run(TriggerType.MANUAL);

        assertThat(inf.getFirstCollectedAt()).isEqualTo(NOW);
        assertThat(inf.getLastCollectedAt()).isEqualTo(NOW);
    }

    @Test
    void 추적_방문이면_first_collected_at은_유지되고_last_collected_at만_갱신된다() {
        wireCommon();

        Instant original = NOW.minus(Duration.ofDays(30));
        Influencer inf = influencer(1L, "alice", original, original);
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        wireProfile("alice", 1000L, "USR1");

        job().run(TriggerType.MANUAL);

        assertThat(inf.getFirstCollectedAt()).isEqualTo(original); // 변경 없음
        assertThat(inf.getLastCollectedAt()).isEqualTo(NOW);
    }

    // ---------------------------------------------------------------------
    // 9b) 댓글 스위치 — comments-enabled=false(운영 기본)면 댓글 단계를 통째로 건너뛴다.
    // ---------------------------------------------------------------------
    @Test
    void 댓글_수집이_꺼져있으면_댓글_페처_호출_없이_게시물만_수집한다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null);
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        wireSelfProfile("alice", 1000L, "USR1", List.of(
                selfTimelineNode("NO_CMT", RECENT, "", false)));

        var summary = job(false).run(TriggerType.MANUAL);

        assertThat(summary.postsUpserted()).isEqualTo(1);
        assertThat(summary.postsCollected()).isEqualTo(0);
        assertThat(contentStore.get("NO_CMT").getStatus()).isEqualTo(ContentStatus.PENDING);
        verify(commentSource, never()).current();          // 댓글 소스 자체를 안 건드림
        verify(rawComments, never()).save(any());
        assertThat(inf.getLastCollectedAt()).isEqualTo(NOW); // 방문 자체는 정상 완료
    }

    // ---------------------------------------------------------------------
    // 10) 댓글 대상은 이번 방문 열거분이 아니라 인플루언서의 PENDING 전체다 (Finding 1)
    // ---------------------------------------------------------------------
    @Test
    void 첫_방문에서_댓글_실패한_게시물이_다음_방문에서_다시_댓글_대상이_된다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null); // 첫 방문
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        when(profileSourceSelector.currentSource()).thenReturn(RawSource.SELF_GQL);
        // 1차 방문: 타임라인에 OLD_FAIL — 2차 방문: 최근 12개에서 밀려나 빈 타임라인
        when(profileSourceSelector.fetchAndSupplement(eq(JobName.COLLECT), eq(List.of("alice")), eq(TriggerType.MANUAL)))
                .thenReturn(new CrawlExecutor.Execution(1L, List.of(selfPayload("alice", 1000L, "USR1",
                        List.of(selfTimelineNode("OLD_FAIL", OLD, "", false))))),
                        new CrawlExecutor.Execution(2L, List.of(selfPayload("alice", 1000L, "USR1",
                        List.of()))));

        CommentFetcher fakeCommentFetcher = mock(CommentFetcher.class);
        when(commentSource.currentSource()).thenReturn(RawSource.SELF_GQL);
        when(commentSource.current()).thenReturn(fakeCommentFetcher);
        when(fakeCommentFetcher.fetch(eq(List.of("OLD_FAIL")), eq(50), eq(TriggerType.MANUAL)))
                .thenThrow(new ApifyException("일시 차단"))
                .thenReturn(new CommentFetcher.CommentResult(200L,
                        Map.of("OLD_FAIL", List.of(Map.of("p", 1)))));

        // 1차: 첫 방문 — 열거는 성공(OLD_FAIL upsert)하지만 댓글 수집 자체가 실패해 PENDING에 머문다.
        job().run(TriggerType.MANUAL);
        Content afterFirst = contentStore.get("OLD_FAIL");
        assertThat(afterFirst.getStatus()).isEqualTo(ContentStatus.PENDING);
        assertThat(afterFirst.getCollectAttempts()).isEqualTo(1);
        assertThat(inf.getFirstCollectedAt()).isEqualTo(NOW); // 열거 성공 = 첫 방문 완료 표식(댓글 실패와 무관)

        // 2차: 재방문 — 이번 열거는 빈 타임라인(OLD_FAIL 재열거 없음)지만, PENDING 조회로 다시 댓글 대상이 되어 성공한다.
        var summary2 = job().run(TriggerType.MANUAL);
        Content afterSecond = contentStore.get("OLD_FAIL");
        assertThat(afterSecond.getStatus()).isEqualTo(ContentStatus.COLLECTED);
        assertThat(summary2.postsCollected()).isEqualTo(1);
    }

    @Test
    void 이번_열거에_안_잡힌_기존_PENDING_content도_방문시_댓글_대상에_포함된다() {
        wireCommon();

        // 첫 방문은 이미 오래 전 완료(재방문) — 이번 열거는 새로 아무것도 안 내놓는다.
        Influencer inf = influencer(1L, "alice", NOW.minus(Duration.ofDays(60)), NOW.minus(Duration.ofDays(60)));
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        wireSelfProfile("alice", 1000L, "USR1", List.of()); // 빈 타임라인

        // 이전 열거로 이미 승격돼 있던(origin=ENUMERATION), 이번 열거에 안 잡히는 오래된 PENDING content.
        Content oldPending = new Content("OLD_PENDING", ContentType.FEED, "alice", 1L,
                NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofDays(90)), ContentOrigin.ENUMERATION);
        oldPending.setId(3000L);
        contentStore.put("OLD_PENDING", oldPending);

        CommentFetcher fakeCommentFetcher = mock(CommentFetcher.class);
        when(commentSource.currentSource()).thenReturn(RawSource.SELF_GQL);
        when(commentSource.current()).thenReturn(fakeCommentFetcher);
        when(fakeCommentFetcher.fetch(eq(List.of("OLD_PENDING")), eq(50), eq(TriggerType.MANUAL)))
                .thenReturn(new CommentFetcher.CommentResult(300L,
                        Map.of("OLD_PENDING", List.of(Map.of("p", 1)))));

        var summary = job().run(TriggerType.MANUAL);

        verify(contents, never()).findByShortCodeIn(argThat(codes -> codes.contains("OLD_PENDING"))); // 이번 열거 upsert 경로는 타지 않았다(윈도우 밖)
        assertThat(oldPending.getStatus()).isEqualTo(ContentStatus.COLLECTED); // 그런데도 댓글 대상엔 포함됐다
        assertThat(summary.postsCollected()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // 11) 방문 단위 트랜잭션 — ApifyException이 아닌 RuntimeException도 해당 방문만 실패
    //     처리하고 다음 인플루언서로 계속한다 (Finding 3)
    // ---------------------------------------------------------------------
    @Test
    void 비ApifyException_런타임오류도_해당_방문만_실패처리하고_다음_인플루언서를_계속한다() {
        wireCommon();

        Influencer bad = influencer(1L, "bad_user", null, null);
        Influencer good = influencer(2L, "good_user", null, null);
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(bad, good));

        when(profileSourceSelector.currentSource()).thenReturn(RawSource.APIFY_ACTOR);
        when(profileSourceSelector.fetchAndSupplement(eq(JobName.COLLECT), eq(List.of("bad_user")), eq(TriggerType.MANUAL)))
                .thenThrow(new IllegalStateException("예상 못한 런타임 오류")); // ApifyException이 아님
        wireProfile("good_user", 1000L, "U2");

        var summary = job().run(TriggerType.MANUAL);

        assertThat(summary.failedVisits()).isEqualTo(1);
        assertThat(summary.visited()).isEqualTo(1);
        assertThat(bad.getLastCollectedAt()).isNull();          // 실패 방문 — 시각 미갱신
        assertThat(good.getLastCollectedAt()).isEqualTo(NOW);   // 다음 인플루언서는 정상 처리
    }

    // ---------------------------------------------------------------------
    // 12) origin 도입 — 열거 upsert가 발굴 부산물을 정식 수집 대상으로 승격시키고,
    //     댓글 대상 조회는 승격되지 않은 발굴 부산물(DISCOVERY)을 제외한다
    // ---------------------------------------------------------------------
    @Test
    void 열거_upsert가_기존_DISCOVERY_행을_ENUMERATION으로_승격시킨다() {
        wireCommon();

        Influencer inf = influencer(1L, "alice", null, null); // backfill 방문
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        wireSelfProfile("alice", 1000L, "USR1", List.of(
                selfTimelineNode("PROMOTE1", RECENT, "", false)));

        // discover가 이미 만들어둔 발굴 부산물 — 이번 열거가 같은 shortCode를 다시 잡는다.
        Content discovered = new Content("PROMOTE1", ContentType.FEED, "alice", 1L,
                RECENT, RECENT, ContentOrigin.DISCOVERY);
        discovered.setId(4000L);
        contentStore.put("PROMOTE1", discovered);

        job().run(TriggerType.MANUAL);

        // 새 행을 만드는 게 아니라 기존 행을 승격 — 저장소의 PROMOTE1은 여전히 같은 객체(인스턴스)다.
        Content promoted = contentStore.get("PROMOTE1");
        assertThat(promoted).isSameAs(discovered);
        assertThat(promoted.getOrigin()).isEqualTo(ContentOrigin.ENUMERATION);
    }

    @Test
    void 댓글_대상_조회는_승격되지_않은_DISCOVERY_PENDING을_제외한다() {
        wireCommon();

        // 추적 방문(첫 방문 완료) — 이번 열거는 새로 아무것도 안 내놓아 기존 PENDING만이 댓글 대상 후보다.
        Influencer inf = influencer(1L, "alice", NOW.minus(Duration.ofDays(60)), NOW.minus(Duration.ofDays(60)));
        when(influencers.findCollectTargets(any(), any())).thenReturn(List.of(inf));
        wireSelfProfile("alice", 1000L, "USR1", List.of()); // 빈 타임라인

        Content discoveryPending = new Content("DISC_PEND", ContentType.FEED, "alice", 1L,
                NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofDays(90)), ContentOrigin.DISCOVERY);
        discoveryPending.setId(5000L);
        contentStore.put("DISC_PEND", discoveryPending);

        Content enumPending = new Content("ENUM_PEND", ContentType.FEED, "alice", 1L,
                NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofDays(90)), ContentOrigin.ENUMERATION);
        enumPending.setId(5001L);
        contentStore.put("ENUM_PEND", enumPending);

        CommentFetcher fakeCommentFetcher = mock(CommentFetcher.class);
        when(commentSource.currentSource()).thenReturn(RawSource.SELF_GQL);
        when(commentSource.current()).thenReturn(fakeCommentFetcher);
        when(fakeCommentFetcher.fetch(eq(List.of("ENUM_PEND")), eq(50), eq(TriggerType.MANUAL)))
                .thenReturn(new CommentFetcher.CommentResult(300L,
                        Map.of("ENUM_PEND", List.of(Map.of("p", 1)))));

        var summary = job().run(TriggerType.MANUAL);

        assertThat(enumPending.getStatus()).isEqualTo(ContentStatus.COLLECTED);
        assertThat(summary.postsCollected()).isEqualTo(1);
        // 발굴 부산물은 댓글 대상에서 아예 빠졌으므로 시도 횟수·상태가 그대로다.
        assertThat(discoveryPending.getStatus()).isEqualTo(ContentStatus.PENDING);
        assertThat(discoveryPending.getCollectAttempts()).isEqualTo(0);
        verify(fakeCommentFetcher, never()).fetch(argThat(codes -> codes.contains("DISC_PEND")), anyInt(), any());
    }
}
