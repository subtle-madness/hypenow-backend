package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.application.service.ContentCaptionUpserter;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentOrigin;
import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawMediaPageRepository;
import com.celfit.crawler.crawling.application.port.out.UserMediaPageFetcher;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawMediaPage;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.ReelsSourceSetting;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.settings.domain.ReelsSource;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ReelsJobTest {

    static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Instant RECENT = Instant.parse("2026-07-10T00:00:00Z");

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    RawMediaPageRepository rawMediaPages = mock(RawMediaPageRepository.class);
    ContentRepository contents = mock(ContentRepository.class);
    ContentCaptionUpserter captionUpserter = mock(ContentCaptionUpserter.class);
    CrawlExecutor executor = mock(CrawlExecutor.class);
    SettingsService settings = mock(SettingsService.class);
    ReelsSourceSetting reelsSource = mock(ReelsSourceSetting.class);
    JobProgress progress = mock(JobProgress.class);
    // 실객체 주입 — execute()가 콜백을 즉시 실행하므로 방문 단위 트랜잭션 래핑을 그대로 재현한다.
    TransactionTemplate txTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));

    AtomicLong runIdSeq = new AtomicLong(100);
    Map<String, Content> contentStore = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void wireCommon() {
        when(settings.reelsBatchLimit()).thenReturn(10);
        when(settings.revisitIntervalDays()).thenReturn(7);
        when(reelsSource.current()).thenReturn(ReelsSource.HIKER);
        when(executor.execute(any(), any(), any(), any(), any(), any(Supplier.class)))
                .thenAnswer(inv -> {
                    Supplier<ApifyResult> work = inv.getArgument(5);
                    return new CrawlExecutor.Execution(runIdSeq.incrementAndGet(), work.get().items());
                });
        when(contents.save(any())).thenAnswer(inv -> {
            Content c = inv.getArgument(0);
            contentStore.put(c.getShortCode(), c);
            return c;
        });
        when(contents.findByShortCode(any())).thenAnswer(inv ->
                Optional.ofNullable(contentStore.get((String) inv.getArgument(0))));
        when(contents.saveAll(any())).thenAnswer(inv -> {
            java.util.List<Content> batch = new java.util.ArrayList<>();
            for (Content c : (Iterable<Content>) inv.getArgument(0)) {
                contentStore.put(c.getShortCode(), c);
                batch.add(c);
            }
            return batch;
        });
        when(contents.findByShortCodeIn(any())).thenAnswer(inv -> {
            java.util.Collection<String> codes = inv.getArgument(0);
            return codes.stream().map(contentStore::get).filter(java.util.Objects::nonNull).toList();
        });
        when(influencers.save(any(Influencer.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    JobStopFlag stopFlag = new JobStopFlag();

    ReelsJob job(List<UserMediaPageFetcher> fetchers) {
        return new ReelsJob(influencers, rawMediaPages, new ContentUpserter(contents, CLOCK),
                captionUpserter, fetchers, executor, settings, reelsSource, CLOCK, progress,
                stopFlag, txTemplate);
    }

    @Test
    void 중지_요청이_있으면_방문하지_않는다() {
        when(influencers.findReelsTargets(any(), any())).thenReturn(List.of(beautyTarget(1L, "a", "pk1")));
        stopFlag.request(JobName.REELS);

        // 페처 없이도 방문 자체가 스킵되므로 예외·실패 카운트 없이 조기 종료된다
        var summary = job(List.of()).run(TriggerType.MANUAL);

        assertThat(summary.visited()).isZero();
        assertThat(summary.failedVisits()).isZero();
    }

    static Influencer beautyTarget(Long id, String username, String pk) {
        Influencer inf = new Influencer(username);
        inf.setId(id);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.setBeauty(true);
        inf.setIgUserId(pk);
        return inf;
    }

    static Map<String, Object> clipsItem(String code, Instant takenAt) {
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("code", code);
        media.put("taken_at", takenAt.getEpochSecond());
        media.put("product_type", "clips");
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put("media", media);
        return wrap;
    }

    static Map<String, Object> clipsPage(List<Map<String, Object>> items) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("response", resp);
        return m;
    }

    /** userId별 페이지 1개를 돌려주는 fake — 없는 userId 호출은 즉시 실패로 과다 호출을 잡는다. */
    static UserMediaPageFetcher clipsFetcher(Map<String, Map<String, Object>> pageByUserId) {
        return new UserMediaPageFetcher() {
            @Override
            public RawSource source() {
                return RawSource.HIKER_V2_CLIPS;
            }

            @Override
            public Map<String, Object> fetchPage(String userId, String cursor) {
                Map<String, Object> page = pageByUserId.get(userId);
                if (page == null) throw new AssertionError("예상 밖 clips 호출: " + userId);
                return page;
            }
        };
    }

    @Test
    void 뷰티_대상에_클립_1페이지를_수집하고_last_reels_at을_북키핑한다() {
        Influencer inf = beautyTarget(1L, "alice", "PK1");
        when(influencers.findReelsTargets(any(), any())).thenReturn(List.of(inf));
        Map<String, Object> page = clipsPage(List.of(clipsItem("R1", RECENT), clipsItem("R2", RECENT)));

        var s = job(List.of(clipsFetcher(Map.of("PK1", page)))).run(TriggerType.MANUAL);

        assertThat(s.visited()).isEqualTo(1);
        assertThat(s.postsUpserted()).isEqualTo(2);
        assertThat(inf.getLastReelsAt()).isEqualTo(NOW);
        verify(influencers).save(inf);

        ArgumentCaptor<RawMediaPage> captor = ArgumentCaptor.forClass(RawMediaPage.class);
        verify(rawMediaPages).save(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo(RawSource.HIKER_V2_CLIPS);
        assertThat(captor.getValue().getInfluencerId()).isEqualTo(1L);
        assertThat(captor.getValue().getPayload()).isEqualTo(page);
        assertThat(contentStore).containsKeys("R1", "R2");
        assertThat(contentStore.get("R1").getOrigin()).isEqualTo(ContentOrigin.ENUMERATION);
    }

    /**
     * raw 원형과 캡션이 같은 capturedAt을 공유해야 한다(clock.instant() 재호출 회귀 가드).
     * 이 클래스 공용 CLOCK은 Clock.fixed라 매 호출이 같은 값을 반환 — capturedAt을 지역
     * 변수로 공유하든 clock.instant()를 두 번 부르든 결과가 똑같아 회귀를 못 잡는다. 그래서
     * 이 테스트만 호출마다 다른 값을 주는 ticking mock Clock을 ReelsJob에 직접 주입한다
     * (ContentUpserter는 영향받지 않게 별도로 공용 CLOCK을 그대로 쓴다).
     *
     * <p>tick 생성기는 유한 목록이 아니라 호출마다 새 값을 뽑는 무한 시퀀스여야 한다 — 유한
     * 목록(예: tick1,tick2,tick3,tick3,tick3)은 Mockito가 소진 후 마지막 값을 반복하므로,
     * 나중에 capturedAt 대입 이전에 clock.instant() 호출이 늘어나면(로깅·새 체크 등) 회귀
     * 시나리오의 두 호출이 둘 다 "소진 후 반복" 구간에 걸려 우연히 같아져 테스트가 회귀를
     * 놓친 채 계속 통과한다(구현 중 실제로 이 함정에 두 번 걸림). 그래서 몇 번을 부르든,
     * 몇 번째 호출인지와 무관하게 "서로 다른 두 호출은 항상 다른 값"만 보장하면 된다 —
     * 단정도 특정 tick 번호가 아니라 캡처한 값끼리 비교한다.
     */
    @Test
    void raw_원형과_캡션이_같은_capturedAt을_공유한다() {
        Clock tickingClock = mock(Clock.class);
        AtomicLong seq = new AtomicLong();
        when(tickingClock.instant()).thenAnswer(inv -> NOW.plusSeconds(seq.incrementAndGet()));
        when(tickingClock.getZone()).thenReturn(ZoneOffset.UTC);   // RevisitCutoff.boundary가 LocalDate.now(clock)에 씀

        Influencer inf = beautyTarget(1L, "alice", "PK1");
        when(influencers.findReelsTargets(any(), any())).thenReturn(List.of(inf));
        Map<String, Object> page = clipsPage(List.of(clipsItem("RT1", RECENT)));

        ReelsJob job = new ReelsJob(influencers, rawMediaPages,
                new ContentUpserter(contents, CLOCK), captionUpserter,
                List.of(clipsFetcher(Map.of("PK1", page))), executor, settings, reelsSource,
                tickingClock, progress, stopFlag, txTemplate);

        job.run(TriggerType.MANUAL);

        ArgumentCaptor<RawMediaPage> pageCaptor = ArgumentCaptor.forClass(RawMediaPage.class);
        verify(rawMediaPages).save(pageCaptor.capture());
        ArgumentCaptor<Instant> capturedAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(captionUpserter).upsert(any(), eq(RawSource.HIKER_V2_CLIPS), capturedAtCaptor.capture());

        // 핵심 불변식: raw 원형과 캡션이 같은 capturedAt을 공유해야 한다 — 각자 새로 불렀다면
        // (무한 생성기라) 반드시 서로 다른 값을 받아 어긋난다. 특정 tick 번호는 하드코딩하지 않는다.
        assertThat(capturedAtCaptor.getValue()).isEqualTo(pageCaptor.getValue().getCapturedAt());
    }

    @Test
    void 대상_조회는_달력일_기준_경계와_배치_한도로_호출한다() {
        // NOW = 2026-07-15T00:00Z, 주기 7일 → 경계 = 오늘 자정 − 6일 = 2026-07-09 자정 (달력일 기준)
        when(settings.reelsBatchLimit()).thenReturn(3);
        when(influencers.findReelsTargets(any(), any())).thenReturn(List.of());

        job(List.of()).run(TriggerType.MANUAL);

        verify(influencers).findReelsTargets(
                eq(Instant.parse("2026-07-09T00:00:00Z")), eq(PageRequest.of(0, 3)));
    }

    @Test
    void pk_없는_계정은_스킵하고_클립을_호출하지_않는다() {
        Influencer noPk = beautyTarget(1L, "no_pk_user", null);
        when(influencers.findReelsTargets(any(), any())).thenReturn(List.of(noPk));

        var s = job(List.of(clipsFetcher(Map.of()))).run(TriggerType.MANUAL);  // 어떤 호출도 즉시 실패

        assertThat(s.skippedNoPk()).isEqualTo(1);
        assertThat(s.visited()).isZero();
        assertThat(s.failedVisits()).isZero();
        assertThat(noPk.getLastReelsAt()).isNull();  // 미수확 유지 — pk 채워지면 다음 실행에서 잡힌다
    }

    @Test
    void 방문_실패는_격리되고_다음_계정을_계속한다() {
        Influencer bad = beautyTarget(1L, "bad", "PK_BAD");
        Influencer good = beautyTarget(2L, "good", "PK_GOOD");
        when(influencers.findReelsTargets(any(), any())).thenReturn(List.of(bad, good));
        UserMediaPageFetcher fetcher = new UserMediaPageFetcher() {
            @Override
            public RawSource source() {
                return RawSource.HIKER_V2_CLIPS;
            }

            @Override
            public Map<String, Object> fetchPage(String userId, String cursor) {
                if ("PK_BAD".equals(userId)) throw new ApifyException("차단됨");
                return clipsPage(List.of(clipsItem("G1", RECENT)));
            }
        };

        var s = job(List.of(fetcher)).run(TriggerType.MANUAL);

        assertThat(s.failedVisits()).isEqualTo(1);
        assertThat(s.visited()).isEqualTo(1);
        assertThat(bad.getLastReelsAt()).isNull();       // 실패 방문 — 다음 실행 재시도
        assertThat(good.getLastReelsAt()).isEqualTo(NOW);
    }

    @Test
    void 릴스가_없는_계정의_404는_수확_완료로_마킹해_재시도_루프를_막는다() {
        Influencer noClips = beautyTarget(1L, "no_clips_user", "PK1");
        when(influencers.findReelsTargets(any(), any())).thenReturn(List.of(noClips));
        UserMediaPageFetcher fetcher = new UserMediaPageFetcher() {
            @Override
            public RawSource source() {
                return RawSource.HIKER_V2_CLIPS;
            }

            @Override
            public Map<String, Object> fetchPage(String userId, String cursor) {
                throw new ApifyException("Hiker HTTP 404: {\"detail\":\"Entries not found\"}");
            }
        };

        var s = job(List.of(fetcher)).run(TriggerType.MANUAL);

        assertThat(s.failedVisits()).isZero();          // 실패가 아니라 '릴스 없음' 확정
        assertThat(s.visited()).isEqualTo(1);
        assertThat(s.postsUpserted()).isZero();
        assertThat(noClips.getLastReelsAt()).isEqualTo(NOW);  // 마킹 — 다음 실행에서 재선정 안 됨
        verify(influencers).save(noClips);
    }

    @Test
    void 릴스없음_404는_crawl_run을_FAILED로_기록하지_않는다() {
        // 실제 CrawlExecutor는 콜백이 던지면 run을 FAILED로 마감한 뒤 재-throw한다 — 그 마감 규칙만
        // 흉내내서, '릴스 없음' 판정이 executor 콜백 밖(예외 경로)이 아니라 안(빈 결과)에서 나는지 검증.
        var runFailed = new java.util.concurrent.atomic.AtomicBoolean();
        when(executor.execute(any(), any(), any(), any(), any(), any(Supplier.class)))
                .thenAnswer(inv -> {
                    Supplier<ApifyResult> work = inv.getArgument(5);
                    try {
                        return new CrawlExecutor.Execution(runIdSeq.incrementAndGet(), work.get().items());
                    } catch (ApifyException e) {
                        runFailed.set(true);
                        throw e;
                    }
                });
        Influencer noClips = beautyTarget(1L, "no_clips_user", "PK1");
        when(influencers.findReelsTargets(any(), any())).thenReturn(List.of(noClips));
        UserMediaPageFetcher fetcher = new UserMediaPageFetcher() {
            @Override
            public RawSource source() {
                return RawSource.HIKER_V2_CLIPS;
            }

            @Override
            public Map<String, Object> fetchPage(String userId, String cursor) {
                throw new com.celfit.crawler.crawling.application.port.out.NotFoundException(
                        "Hiker HTTP 404: {\"detail\":\"Entries not found\"}");
            }
        };

        var s = job(List.of(fetcher)).run(TriggerType.MANUAL);

        assertThat(runFailed).isFalse();                     // 양성 케이스 — FAILED 배지 금지
        assertThat(s.failedVisits()).isZero();
        assertThat(noClips.getLastReelsAt()).isEqualTo(NOW);
    }

    @Test
    void 이미_있는_DISCOVERY_행은_새로_만들지_않고_ENUMERATION으로_승격한다() {
        Influencer inf = beautyTarget(1L, "alice", "PK1");
        when(influencers.findReelsTargets(any(), any())).thenReturn(List.of(inf));
        Content discovered = new Content("R_DUP", com.celfit.crawler.content.domain.ContentType.REELS,
                "alice", 1L, RECENT, RECENT, ContentOrigin.DISCOVERY);
        contentStore.put("R_DUP", discovered);
        Map<String, Object> page = clipsPage(List.of(clipsItem("R_DUP", RECENT)));

        job(List.of(clipsFetcher(Map.of("PK1", page)))).run(TriggerType.MANUAL);

        assertThat(contentStore.get("R_DUP")).isSameAs(discovered);
        assertThat(discovered.getOrigin()).isEqualTo(ContentOrigin.ENUMERATION);
    }

    static Map<String, Object> actorItem(String code, String isoTimestamp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shortCode", code);
        m.put("timestamp", isoTimestamp);
        m.put("productType", "clips");
        m.put("caption", "cap " + code);
        return m;
    }

    @Test
    void 액터_소스면_계정당_액터_런으로_수집하고_APIFY_ACTOR로_저장한다() {
        when(reelsSource.current()).thenReturn(ReelsSource.ACTOR);
        when(settings.reelsActorResultsLimit()).thenReturn(6);
        Influencer inf = beautyTarget(1L, "alice", "PK1");
        when(influencers.findReelsTargets(any(), any())).thenReturn(List.of(inf));
        List<Map<String, Object>> items = List.of(
                actorItem("A1", "2026-07-10T00:00:00Z"), actorItem("A2", "2026-07-10T00:00:00Z"));
        when(executor.execute(eq(JobName.REELS), any(), isNull(), eq("alice"),
                eq(Actors.DETAIL_REELS), anyMap()))
                .thenReturn(new CrawlExecutor.Execution(runIdSeq.incrementAndGet(), items));

        var s = job(List.of()).run(TriggerType.MANUAL);   // 액터 경로 — Hiker 페처 불필요

        assertThat(s.visited()).isEqualTo(1);
        assertThat(s.postsUpserted()).isEqualTo(2);
        assertThat(inf.getLastReelsAt()).isEqualTo(NOW);

        ArgumentCaptor<RawMediaPage> captor = ArgumentCaptor.forClass(RawMediaPage.class);
        verify(rawMediaPages).save(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo(RawSource.APIFY_ACTOR);
        assertThat(captor.getValue().getPayload()).isEqualTo(Map.of("items", items));
        assertThat(contentStore).containsKeys("A1", "A2");
        verify(captionUpserter).upsert(any(), eq(RawSource.APIFY_ACTOR), any());
    }

    @Test
    void 액터_소스는_pk_없어도_수집한다() {
        when(reelsSource.current()).thenReturn(ReelsSource.ACTOR);
        when(settings.reelsActorResultsLimit()).thenReturn(6);
        Influencer noPk = beautyTarget(1L, "no_pk_user", null);
        when(influencers.findReelsTargets(any(), any())).thenReturn(List.of(noPk));
        when(executor.execute(eq(JobName.REELS), any(), isNull(), eq("no_pk_user"),
                eq(Actors.DETAIL_REELS), anyMap()))
                .thenReturn(new CrawlExecutor.Execution(runIdSeq.incrementAndGet(),
                        List.of(actorItem("N1", "2026-07-10T00:00:00Z"))));

        var s = job(List.of()).run(TriggerType.MANUAL);

        assertThat(s.skippedNoPk()).isZero();   // 액터는 username 기반 — pk 스킵은 HIKER 전용
        assertThat(s.visited()).isEqualTo(1);
        assertThat(noPk.getLastReelsAt()).isEqualTo(NOW);
    }

    @Test
    void 액터_0건_응답은_수확_완료로_마킹해_재시도_루프를_막는다() {
        when(reelsSource.current()).thenReturn(ReelsSource.ACTOR);
        when(settings.reelsActorResultsLimit()).thenReturn(6);
        Influencer noClips = beautyTarget(1L, "no_clips_user", "PK1");
        when(influencers.findReelsTargets(any(), any())).thenReturn(List.of(noClips));
        when(executor.execute(eq(JobName.REELS), any(), isNull(), eq("no_clips_user"),
                eq(Actors.DETAIL_REELS), anyMap()))
                .thenReturn(new CrawlExecutor.Execution(runIdSeq.incrementAndGet(), List.of()));

        var s = job(List.of()).run(TriggerType.MANUAL);

        assertThat(s.failedVisits()).isZero();
        assertThat(s.visited()).isEqualTo(1);
        assertThat(s.postsUpserted()).isZero();
        assertThat(noClips.getLastReelsAt()).isEqualTo(NOW);
        verify(rawMediaPages, org.mockito.Mockito.never()).save(any());   // 0건은 raw 저장도 없음
    }
}
