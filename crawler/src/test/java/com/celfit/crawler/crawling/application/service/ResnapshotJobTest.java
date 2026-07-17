package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ResnapshotJobTest {

    static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    RawProfileRepository rawProfiles = mock(RawProfileRepository.class);
    SelfProfileFetcher fetcher = mock(SelfProfileFetcher.class);
    SettingsService settings = mock(SettingsService.class);
    TransactionTemplate txTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));

    ResnapshotJob job = new ResnapshotJob(influencers, rawProfiles, fetcher, settings,
            java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC), txTemplate);

    @BeforeEach
    void setUp() {
        when(influencers.save(any(Influencer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(settings.resnapshotBatchLimit()).thenReturn(200);
        when(fetcher.rawSource()).thenReturn(RawSource.SELF_GQL);
    }

    static Influencer notBeauty(Long id, String username) {
        Influencer inf = new Influencer(username);
        inf.setId(id);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.setBeauty(false);
        inf.setBeautySource(Influencer.BEAUTY_SOURCE_CLAUDE);
        return inf;
    }

    static RawProfile hikerProfile(Long influencerId, boolean isPrivate) {
        return new RawProfile(influencerId, null, RawSource.HIKER_MOBILE,
                Map.of("user", Map.of("is_private", isPrivate)), Instant.EPOCH);
    }

    /** SELF_GQL 응답 원형 — username·followers·is_private 경로 포함. */
    static Map<String, Object> gqlItem(String username, long followers) {
        return Map.of("data", Map.of("user", Map.of(
                "username", username, "id", "IG-" + username,
                "edge_followed_by", Map.of("count", followers))));
    }

    @Test
    void 대상의_프로필을_로컬_GQL로_재수집해_raw_profile로_저장한다() {
        Influencer a = notBeauty(1L, "a");
        when(influencers.findResnapshotTargets(eq(InfluencerStatus.QUALIFIED),
                eq(Influencer.BEAUTY_SOURCE_CLAUDE), any(), any())).thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(hikerProfile(1L, false)));
        when(fetcher.fetch(eq(JobName.RESNAPSHOT), eq(List.of("a")), eq(TriggerType.MANUAL)))
                .thenReturn(new CrawlExecutor.Execution(10L, List.of(gqlItem("a", 5000L))));

        var s = job.run(TriggerType.MANUAL);

        assertThat(s.snapshotted()).isEqualTo(1);
        ArgumentCaptor<RawProfile> saved = ArgumentCaptor.forClass(RawProfile.class);
        verify(rawProfiles).save(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo(RawSource.SELF_GQL);
        assertThat(saved.getValue().getInfluencerId()).isEqualTo(1L);
        assertThat(saved.getValue().getCapturedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getFollowers()).isEqualTo(5000L);
        // followers·igUserId 백필 — qualify와 동일 규칙
        assertThat(a.getFollowers()).isEqualTo(5000L);
        assertThat(a.getIgUserId()).isEqualTo("IG-a");
    }

    @Test
    void 비공개_계정은_요청_없이_스킵한다() {
        Influencer a = notBeauty(1L, "a");
        when(influencers.findResnapshotTargets(any(), any(), any(), any())).thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(hikerProfile(1L, true)));

        var s = job.run(TriggerType.MANUAL);

        assertThat(s.skippedPrivate()).isEqualTo(1);
        assertThat(s.snapshotted()).isZero();
        verify(fetcher, never()).fetch(any(), anyList(), any());
    }

    @Test
    void 청크_실패는_격리되고_다음_실행에서_재시도된다() {
        Influencer a = notBeauty(1L, "a");
        when(influencers.findResnapshotTargets(any(), any(), any(), any())).thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(hikerProfile(1L, false)));
        when(fetcher.fetch(any(), anyList(), any())).thenThrow(new ApifyException("연속 rate-limit"));

        var s = job.run(TriggerType.MANUAL);

        assertThat(s.failedChunks()).isEqualTo(1);
        assertThat(s.snapshotted()).isZero();
    }

    @Test
    void 배치_한도와_캡션_없는_소스로_선정_쿼리를_호출한다() {
        when(settings.resnapshotBatchLimit()).thenReturn(30);
        when(influencers.findResnapshotTargets(any(), any(), any(), any())).thenReturn(List.of());

        job.run(TriggerType.MANUAL);

        verify(influencers).findResnapshotTargets(
                eq(InfluencerStatus.QUALIFIED), eq(Influencer.BEAUTY_SOURCE_CLAUDE),
                eq(List.of(RawSource.HIKER_MOBILE, RawSource.DATALIKERS)), eq(PageRequest.of(0, 30)));
    }

    @Test
    void 재수집_404_계정은_소프트_딜리트된다() {
        Influencer a = notBeauty(1L, "a");
        when(influencers.findResnapshotTargets(any(), any(), any(), any())).thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(hikerProfile(1L, false)));
        when(fetcher.fetch(any(), anyList(), any()))
                .thenReturn(new CrawlExecutor.Execution(10L, List.of(), List.of("a")));

        var s = job.run(TriggerType.MANUAL);

        assertThat(a.getStatus()).isEqualTo(InfluencerStatus.DELETED);
        verify(influencers).save(a);
        assertThat(s.snapshotted()).isZero();
    }

    @Test
    void 응답에_없는_계정은_저장하지_않는다() {
        // 인스타가 일부 계정 응답을 빠뜨려도(삭제·차단) 나머지는 저장된다 — 빠진 계정은 다음 실행 재선정
        Influencer a = notBeauty(1L, "a");
        Influencer b = notBeauty(2L, "b");
        when(influencers.findResnapshotTargets(any(), any(), any(), any())).thenReturn(List.of(a, b));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(any()))
                .thenReturn(Optional.of(hikerProfile(1L, false)));
        when(fetcher.fetch(any(), anyList(), any()))
                .thenReturn(new CrawlExecutor.Execution(10L, List.of(gqlItem("a", 5000L))));

        var s = job.run(TriggerType.MANUAL);

        assertThat(s.snapshotted()).isEqualTo(1);
    }
}
