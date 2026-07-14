package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

class QualifyJobTest {

    static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    RawProfileRepository rawProfiles = mock(RawProfileRepository.class);
    ProfileSourceSelector selector = mock(ProfileSourceSelector.class);
    SettingsService settings = mock(SettingsService.class);

    QualifyJob job = new QualifyJob(influencers, rawProfiles, selector, settings, CLOCK);

    @BeforeEach
    void wireDefaults() {
        when(settings.qualifyMinFollowers()).thenReturn(3000);
        when(settings.qualifyMaxFollowers()).thenReturn(50000);
    }

    static Influencer influencer(Long id, String username, InfluencerStatus status,
                                  Long followers, Instant lastProfiledAt) {
        Influencer inf = new Influencer(username);
        inf.setId(id);
        inf.setStatus(status);
        inf.setFollowers(followers);
        inf.setLastProfiledAt(lastProfiledAt);
        return inf;
    }

    /** 원형 예시: {"user":{"username":"alice","follower_count":12345,"pk":"111"}} */
    static Map<String, Object> hikerItem(String username, long followerCount, String pk) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        user.put("follower_count", followerCount);
        user.put("pk", pk);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("user", user);
        return root;
    }

    @Test
    void DISCOVERED_인플루언서가_배치_상한만큼_선정된다() {
        when(settings.qualifyBatchLimit()).thenReturn(7);
        when(influencers.findByStatus(InfluencerStatus.DISCOVERED, PageRequest.of(0, 7)))
                .thenReturn(new ArrayList<>());

        job.run(TriggerType.MANUAL, false);

        verify(influencers).findByStatus(InfluencerStatus.DISCOVERED, PageRequest.of(0, 7));
        verify(influencers, never()).findByStatus(eq(InfluencerStatus.QUALIFIED), any());
        verify(influencers, never()).findByStatus(eq(InfluencerStatus.EXCLUDED), any());
    }

    @Test
    void 프로필_원형이_raw_profile에_source와_함께_저장되고_followers_username_실컬럼이_채워진다() {
        when(settings.qualifyBatchLimit()).thenReturn(50);
        Influencer alice = influencer(1L, "alice", InfluencerStatus.DISCOVERED, null, null);
        when(influencers.findByStatus(InfluencerStatus.DISCOVERED, PageRequest.of(0, 50)))
                .thenReturn(new ArrayList<>(List.of(alice)));
        when(selector.currentSource()).thenReturn(RawSource.HIKER_MOBILE);
        Map<String, Object> item = hikerItem("alice", 12345L, "111");
        when(selector.fetchAndSupplement(List.of("alice"), TriggerType.MANUAL))
                .thenReturn(new CrawlExecutor.Execution(99L, List.of(item)));

        var summary = job.run(TriggerType.MANUAL, false);

        ArgumentCaptor<RawProfile> captor = ArgumentCaptor.forClass(RawProfile.class);
        verify(rawProfiles).save(captor.capture());
        RawProfile rp = captor.getValue();
        assertThat(rp.getInfluencerId()).isEqualTo(1L);
        assertThat(rp.getCrawlRunId()).isEqualTo(99L);
        assertThat(rp.getSource()).isEqualTo(RawSource.HIKER_MOBILE);
        assertThat(rp.getPayload()).isEqualTo(item); // 원형 그대로
        assertThat(rp.getUsername()).isEqualTo("alice");
        assertThat(rp.getFollowers()).isEqualTo(12345L);
        assertThat(rp.getCapturedAt()).isEqualTo(NOW);

        assertThat(alice.getFollowers()).isEqualTo(12345L);
        assertThat(alice.getLastProfiledAt()).isEqualTo(NOW);
        assertThat(summary.profiled()).isEqualTo(1);
    }

    @Test
    void 프로필_수집시_igUserId가_원형에서_추출되어_저장된다() {
        when(settings.qualifyBatchLimit()).thenReturn(50);
        Influencer alice = influencer(1L, "alice", InfluencerStatus.DISCOVERED, null, null);
        when(influencers.findByStatus(InfluencerStatus.DISCOVERED, PageRequest.of(0, 50)))
                .thenReturn(new ArrayList<>(List.of(alice)));
        when(selector.currentSource()).thenReturn(RawSource.HIKER_MOBILE);
        when(selector.fetchAndSupplement(List.of("alice"), TriggerType.MANUAL))
                .thenReturn(new CrawlExecutor.Execution(99L, List.of(hikerItem("alice", 12345L, "111"))));

        job.run(TriggerType.MANUAL, false);

        // collect 열거 API 파라미터로 쓰인다 — 방문 시 프로필 갱신이 실패해도 열거를 계속할 폴백.
        assertThat(alice.getIgUserId()).isEqualTo("111");
    }

    @Test
    void followers_갱신후_전역_범위_안이면_QUALIFIED_밖이면_EXCLUDED() {
        when(settings.qualifyBatchLimit()).thenReturn(50);
        Influencer inRange = influencer(1L, "in", InfluencerStatus.DISCOVERED, 10000L, NOW);
        Influencer outRange = influencer(2L, "out", InfluencerStatus.DISCOVERED, 100L, NOW);
        when(influencers.findByStatus(InfluencerStatus.DISCOVERED, PageRequest.of(0, 50)))
                .thenReturn(new ArrayList<>(List.of(inRange, outRange)));

        var summary = job.run(TriggerType.MANUAL, false);

        assertThat(inRange.getStatus()).isEqualTo(InfluencerStatus.QUALIFIED);
        assertThat(outRange.getStatus()).isEqualTo(InfluencerStatus.EXCLUDED);
        assertThat(summary.qualified()).isEqualTo(1);
        assertThat(summary.excluded()).isEqualTo(1);
        assertThat(summary.deferred()).isEqualTo(0);
        // 이미 프로필 있음(lastProfiledAt != null) → 재수집 없음
        verify(selector, never()).fetchAndSupplement(any(), any());
    }

    @Test
    void 프로필_응답에_해당_계정이_없으면_DISCOVERED_유지_deferred() {
        when(settings.qualifyBatchLimit()).thenReturn(50);
        Influencer bob = influencer(1L, "bob", InfluencerStatus.DISCOVERED, null, null);
        when(influencers.findByStatus(InfluencerStatus.DISCOVERED, PageRequest.of(0, 50)))
                .thenReturn(new ArrayList<>(List.of(bob)));
        when(selector.currentSource()).thenReturn(RawSource.HIKER_MOBILE);
        when(selector.fetchAndSupplement(List.of("bob"), TriggerType.MANUAL))
                .thenReturn(new CrawlExecutor.Execution(1L, List.of())); // 응답에 bob 없음

        var summary = job.run(TriggerType.MANUAL, false);

        assertThat(bob.getStatus()).isEqualTo(InfluencerStatus.DISCOVERED);
        assertThat(bob.getFollowers()).isNull();
        assertThat(summary.profiled()).isEqualTo(0);
        assertThat(summary.deferred()).isEqualTo(1);
        assertThat(summary.qualified()).isEqualTo(0);
        assertThat(summary.excluded()).isEqualTo(0);
        verify(rawProfiles, never()).save(any());
    }

    @Test
    void 프로필_청크가_실패하면_failedChunks로_드러나고_deferred로_밀린다() {
        when(settings.qualifyBatchLimit()).thenReturn(50);
        Influencer bob = influencer(1L, "bob", InfluencerStatus.DISCOVERED, null, null);
        when(influencers.findByStatus(InfluencerStatus.DISCOVERED, PageRequest.of(0, 50)))
                .thenReturn(new ArrayList<>(List.of(bob)));
        when(selector.currentSource()).thenReturn(RawSource.SELF_GQL);
        when(selector.fetchAndSupplement(List.of("bob"), TriggerType.MANUAL))
                .thenThrow(new com.celfit.crawler.crawling.application.port.out.ApifyException("401"));

        var summary = job.run(TriggerType.MANUAL, false);

        // 잡은 계속되지만(다음 실행 재시도) 실패가 Summary에 드러난다 — "완료" 로그와 FAILED run의 모순 방지
        assertThat(summary.failedChunks()).isEqualTo(1);
        assertThat(summary.deferred()).isEqualTo(1);
        assertThat(bob.getStatus()).isEqualTo(InfluencerStatus.DISCOVERED);
    }

    @Test
    void requalify_true면_QUALIFIED_EXCLUDED도_기존_followers로_재판정하되_재호출은_없다() {
        when(settings.qualifyBatchLimit()).thenReturn(50);
        when(influencers.findByStatus(InfluencerStatus.DISCOVERED, PageRequest.of(0, 50)))
                .thenReturn(new ArrayList<>());
        Influencer wasQualified = influencer(1L, "q", InfluencerStatus.QUALIFIED, 100L, NOW); // 이제 범위 밖
        Influencer wasExcluded = influencer(2L, "e", InfluencerStatus.EXCLUDED, 10000L, NOW); // 이제 범위 안
        when(influencers.findByStatus(InfluencerStatus.QUALIFIED, Pageable.unpaged()))
                .thenReturn(List.of(wasQualified));
        when(influencers.findByStatus(InfluencerStatus.EXCLUDED, Pageable.unpaged()))
                .thenReturn(List.of(wasExcluded));

        var summary = job.run(TriggerType.MANUAL, true);

        assertThat(wasQualified.getStatus()).isEqualTo(InfluencerStatus.EXCLUDED);
        assertThat(wasExcluded.getStatus()).isEqualTo(InfluencerStatus.QUALIFIED);
        assertThat(summary.qualified()).isEqualTo(1);
        assertThat(summary.excluded()).isEqualTo(1);
        verify(selector, never()).fetchAndSupplement(any(), any());
    }
}
