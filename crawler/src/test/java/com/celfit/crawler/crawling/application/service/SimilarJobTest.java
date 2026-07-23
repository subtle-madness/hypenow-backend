package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.InfluencerDiscoveryRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerDiscovery;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SimilarJobTest {

    static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    InfluencerDiscoveryRepository discoveries = mock(InfluencerDiscoveryRepository.class);
    HikerSuggestedSupplement suggested = mock(HikerSuggestedSupplement.class);
    HikerUserResolver resolver = mock(HikerUserResolver.class);
    CrawlExecutor executor = mock(CrawlExecutor.class);
    com.celfit.crawler.settings.application.service.SettingsService settings =
            mock(com.celfit.crawler.settings.application.service.SettingsService.class);
    // 실객체 주입 — execute()가 콜백을 즉시 실행하므로 시드 단위 트랜잭션 래핑을 그대로 재현한다.
    TransactionTemplate txTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));

    java.util.List<Integer> capturedRequestCounts = new java.util.ArrayList<>();

    JobStopFlag stopFlag = new JobStopFlag();

    SimilarJob job = new SimilarJob(influencers, discoveries, suggested, resolver, executor, settings,
            stopFlag, CLOCK, txTemplate);

    static Influencer seed(Long id, String username, String igUserId) {
        Influencer inf = new Influencer(username);
        inf.setId(id);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.setBeauty(true);
        inf.setIgUserId(igUserId);
        return inf;
    }

    /** executor mock이 supplier를 실제로 실행하게 — pk 해석·requestCount 경로까지 단위 검증. */
    @SuppressWarnings("unchecked")
    @BeforeEach
    void executorRunsSupplier() {
        when(settings.similarBatchLimit()).thenReturn(50);
        when(executor.execute(any(), any(), any(), any(), any(), any(Supplier.class)))
                .thenAnswer(inv -> {
                    ApifyResult r = ((Supplier<ApifyResult>) inv.getArgument(5)).get();
                    capturedRequestCounts.add(r.requestCount());
                    return new CrawlExecutor.Execution(1L, r.items());
                });
        when(influencers.save(any(Influencer.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    @Test
    void 중지_요청이_있으면_시드를_처리하지_않는다() {
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(seed(1L, "seed1", "100")));
        stopFlag.request(JobName.SIMILAR);

        var summary = job.run(TriggerType.MANUAL);

        verify(executor, never()).execute(any(), any(), any(), any(), any(), any(Supplier.class));
        assertThat(summary.processedSeeds()).isZero();
    }

    @Test
    void 유사_계정을_DISCOVERED로_upsert하고_출처를_기록하고_시드를_마킹한다() {
        Influencer s = seed(1L, "seed1", "100");
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(s));
        when(suggested.fetch("100")).thenReturn(new HikerSuggestedSupplement.Suggested(
                List.of(Map.of("username", "new1", "pk", "1"),
                        Map.of("username", "known1", "pk", "2")), Map.of()));
        when(influencers.findByUsername("new1")).thenReturn(Optional.empty());
        Influencer known = seed(9L, "known1", null);
        when(influencers.findByUsername("known1")).thenReturn(Optional.of(known));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.processedSeeds()).isEqualTo(1);
        assertThat(summary.newInfluencers()).isEqualTo(1);
        assertThat(summary.knownInfluencers()).isEqualTo(1);
        assertThat(s.getSimilarProcessedAt()).isEqualTo(NOW);
        ArgumentCaptor<InfluencerDiscovery> d = ArgumentCaptor.forClass(InfluencerDiscovery.class);
        verify(discoveries, org.mockito.Mockito.times(2)).save(d.capture());
        assertThat(d.getAllValues()).allSatisfy(rec -> {
            assertThat(rec.getKeyword()).isEqualTo("유사:seed1");
            assertThat(rec.getDiscoveredPostShortCode()).isNull();
        });
        assertThat(capturedRequestCounts).containsExactly(1);  // pk 보유 — suggested 1회만
    }

    @Test
    void 시드_자신과_run_내_중복은_건너뛴다() {
        Influencer s = seed(1L, "seed1", "100");
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(s));
        when(suggested.fetch("100")).thenReturn(new HikerSuggestedSupplement.Suggested(
                List.of(Map.of("username", "SEED1"),      // 자기 자신 (대소문자 무시)
                        Map.of("username", "dup"),
                        Map.of("username", "dup")), Map.of()));
        when(influencers.findByUsername("dup")).thenReturn(Optional.empty());

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.newInfluencers()).isEqualTo(1);
    }

    @Test
    void igUserId가_없으면_pk를_해석해_백필한다() {
        Influencer s = seed(1L, "seed1", null);
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(s));
        when(resolver.resolvePk("seed1")).thenReturn("777");
        when(suggested.fetch("777")).thenReturn(
                new HikerSuggestedSupplement.Suggested(List.of(), Map.of()));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(s.getIgUserId()).isEqualTo("777");
        assertThat(summary.processedSeeds()).isEqualTo(1);
        assertThat(capturedRequestCounts).containsExactly(2);  // pk 해석 1회 + suggested 1회
    }

    @Test
    void pk_해석_실패_시드는_마킹하지_않고_failedSeeds로_남긴다() {
        Influencer s = seed(1L, "seed1", null);
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(s));
        when(resolver.resolvePk("seed1")).thenReturn(null);

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.failedSeeds()).isEqualTo(1);
        assertThat(s.getSimilarProcessedAt()).isNull();
        verify(suggested, never()).fetch(any());
    }

    @Test
    void chaining_불가_403은_수확_불가로_마킹해_재시도하지_않는다() {
        Influencer s = seed(1L, "seed1", "100");
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(s));
        when(suggested.fetch("100")).thenThrow(new ApifyException(
                "Hiker HTTP 403: {\"detail\":\"Not eligible for chaining.\",\"exc_type\":\"InvalidTargetUser\"}"));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.ineligibleSeeds()).isEqualTo(1);
        assertThat(summary.failedSeeds()).isZero();
        assertThat(s.getSimilarProcessedAt()).isEqualTo(NOW);
    }

    @SuppressWarnings("unchecked")
    @Test
    void chaining_불가는_crawl_run을_FAILED로_기록하지_않는다() {
        // 실제 CrawlExecutor는 콜백이 던지면 run을 FAILED로 마감한 뒤 재-throw한다 — 그 마감 규칙만
        // 흉내내서, '수확 불가' 판정이 콜백 밖(예외 경로)이 아니라 안(빈 결과)에서 나는지 검증.
        var runFailed = new java.util.concurrent.atomic.AtomicBoolean();
        when(executor.execute(any(), any(), any(), any(), any(), any(Supplier.class)))
                .thenAnswer(inv -> {
                    try {
                        ApifyResult r = ((Supplier<ApifyResult>) inv.getArgument(5)).get();
                        return new CrawlExecutor.Execution(1L, r.items());
                    } catch (ApifyException e) {
                        runFailed.set(true);
                        throw e;
                    }
                });
        Influencer s = seed(1L, "seed1", "100");
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(s));
        when(suggested.fetch("100")).thenThrow(new ApifyException(
                "Hiker HTTP 403: {\"detail\":\"Not eligible for chaining.\",\"exc_type\":\"InvalidTargetUser\"}"));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(runFailed).isFalse();                     // 양성 케이스 — FAILED 배지 금지
        assertThat(summary.ineligibleSeeds()).isEqualTo(1);
        assertThat(s.getSimilarProcessedAt()).isEqualTo(NOW);
    }

    @Test
    void 일반_오류_시드는_격리되고_다음_시드는_계속_처리된다() {
        Influencer bad = seed(1L, "bad", "1");
        Influencer good = seed(2L, "good", "2");
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(bad, good));
        when(suggested.fetch("1")).thenThrow(new ApifyException("Hiker HTTP 500: 서버 오류"));
        when(suggested.fetch("2")).thenReturn(
                new HikerSuggestedSupplement.Suggested(List.of(), Map.of()));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.failedSeeds()).isEqualTo(1);
        assertThat(summary.processedSeeds()).isEqualTo(1);
        assertThat(bad.getSimilarProcessedAt()).isNull();
        assertThat(good.getSimilarProcessedAt()).isEqualTo(NOW);
    }

    @Test
    void 일반_실패_시드도_해석된_pk_백필은_저장된다() {
        Influencer s = seed(1L, "seed1", null);
        when(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                eq(InfluencerStatus.QUALIFIED), any())).thenReturn(List.of(s));
        when(resolver.resolvePk("seed1")).thenReturn("777");
        when(suggested.fetch("777")).thenThrow(new ApifyException("Hiker HTTP 500: 서버 오류"));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.failedSeeds()).isEqualTo(1);
        assertThat(s.getIgUserId()).isEqualTo("777");
        assertThat(s.getSimilarProcessedAt()).isNull();
        verify(influencers).save(s);   // 백필 영속
    }
}
