package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentOrigin;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawMediaPageRepository;
import com.celfit.crawler.crawling.application.port.out.UserMediaPageFetcher;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.RawMediaPage;
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
    CrawlExecutor executor = mock(CrawlExecutor.class);
    SettingsService settings = mock(SettingsService.class);
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

    ReelsJob job(List<UserMediaPageFetcher> fetchers) {
        return new ReelsJob(influencers, rawMediaPages, new ContentUpserter(contents, CLOCK),
                fetchers, executor, settings, CLOCK, progress, txTemplate);
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
}
