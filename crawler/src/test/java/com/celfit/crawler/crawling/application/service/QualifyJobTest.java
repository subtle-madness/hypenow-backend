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
import com.celfit.crawler.crawling.domain.JobName;
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
import org.springframework.data.domain.Sort;

class QualifyJobTest {

    static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    RawProfileRepository rawProfiles = mock(RawProfileRepository.class);
    ProfileSourceSelector selector = mock(ProfileSourceSelector.class);
    SettingsService settings = mock(SettingsService.class);
    // 실객체 주입 — execute()가 콜백을 즉시 실행하므로 청크 단위 트랜잭션 래핑을 그대로 재현한다.
    org.springframework.transaction.support.TransactionTemplate txTemplate =
            new org.springframework.transaction.support.TransactionTemplate(
                    mock(org.springframework.transaction.PlatformTransactionManager.class));

    JobStopFlag stopFlag = new JobStopFlag();

    QualifyJob job = new QualifyJob(influencers, rawProfiles, selector, settings, stopFlag, CLOCK, txTemplate);

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
    void 중지_요청이_있으면_프로필_청크는_건너뛰고_이미_확보된_판정만_진행한다() {
        when(settings.qualifyBatchLimit()).thenReturn(50);
        Influencer legacy = influencer(1L, "legacy", InfluencerStatus.DISCOVERED, 10000L, NOW);
        when(influencers.findByStatusAndFollowersIsNotNull(InfluencerStatus.DISCOVERED))
                .thenReturn(List.of(legacy));
        Influencer pending = influencer(2L, "pending", InfluencerStatus.DISCOVERED, null, null);
        when(influencers.findByStatusAndFollowersIsNull(eq(InfluencerStatus.DISCOVERED), any(Pageable.class)))
                .thenReturn(List.of(pending));
        stopFlag.request(JobName.QUALIFY);

        var summary = job.run(TriggerType.MANUAL, false);

        verify(selector, never()).fetchAndSupplement(any(), any(), any());  // API 구간만 중단
        assertThat(legacy.getStatus()).isEqualTo(InfluencerStatus.QUALIFIED);  // DB 전용 판정은 진행
        assertThat(summary.deferred()).isEqualTo(1);  // 미확보분은 다음 실행 재시도
    }

    @Test
    void 프로필_404_계정은_소프트_딜리트되어_재선정되지_않는다() {
        when(settings.qualifyBatchLimit()).thenReturn(50);
        Influencer ghost = influencer(1L, "ghost", InfluencerStatus.DISCOVERED, null, null);
        when(influencers.findByStatusAndFollowersIsNull(
                InfluencerStatus.DISCOVERED, PageRequest.of(0, 50, Sort.by("id"))))
                .thenReturn(List.of(ghost));
        when(selector.currentSource()).thenReturn(RawSource.HIKER_MOBILE);
        when(selector.fetchAndSupplement(any(), any(), any()))
                .thenReturn(new CrawlExecutor.Execution(1L, List.of(), List.of("ghost")));

        var summary = job.run(TriggerType.MANUAL, false);

        assertThat(ghost.getStatus()).isEqualTo(InfluencerStatus.DELETED);
        verify(influencers).save(ghost);
        assertThat(summary.deferred()).isZero();  // 소멸 계정은 재시도 대상(deferred)이 아니다
    }

    @Test
    void 프로필_미확보_인플루언서가_id순_배치_상한만큼_선정된다() {
        when(settings.qualifyBatchLimit()).thenReturn(7);

        job.run(TriggerType.MANUAL, false);

        verify(influencers).findByStatusAndFollowersIsNull(
                InfluencerStatus.DISCOVERED, PageRequest.of(0, 7, Sort.by("id")));
        verify(influencers).findByStatusAndFollowersIsNotNull(InfluencerStatus.DISCOVERED);
        verify(influencers, never()).findByStatus(eq(InfluencerStatus.QUALIFIED), any());
        verify(influencers, never()).findByStatus(eq(InfluencerStatus.EXCLUDED), any());
    }

    @Test
    void followers가_이미_있는_DISCOVERED는_배치와_무관하게_전부_즉시_판정된다() {
        // 레거시 이관분처럼 followers가 이미 있는 계정 — API 호출 없이 판정 가능하므로
        // 배치 상한(1)에 안 걸리고 전부 판정돼야 한다.
        when(settings.qualifyBatchLimit()).thenReturn(1);
        Influencer legacyIn = influencer(1L, "legacy_in", InfluencerStatus.DISCOVERED, 10000L, NOW);
        Influencer legacyOut = influencer(2L, "legacy_out", InfluencerStatus.DISCOVERED, 100L, NOW);
        Influencer legacyOut2 = influencer(3L, "legacy_out2", InfluencerStatus.DISCOVERED, 999999L, NOW);
        when(influencers.findByStatusAndFollowersIsNotNull(InfluencerStatus.DISCOVERED))
                .thenReturn(List.of(legacyIn, legacyOut, legacyOut2));

        var summary = job.run(TriggerType.MANUAL, false);

        assertThat(legacyIn.getStatus()).isEqualTo(InfluencerStatus.QUALIFIED);
        assertThat(legacyOut.getStatus()).isEqualTo(InfluencerStatus.EXCLUDED);
        assertThat(legacyOut2.getStatus()).isEqualTo(InfluencerStatus.EXCLUDED);
        assertThat(summary.qualified()).isEqualTo(1);
        assertThat(summary.excluded()).isEqualTo(2);
        verify(selector, never()).fetchAndSupplement(any(), any(), any()); // 재호출 없음
    }

    @Test
    void 프로필_원형이_raw_profile에_source와_함께_저장되고_followers_username_실컬럼이_채워진다() {
        when(settings.qualifyBatchLimit()).thenReturn(50);
        Influencer alice = influencer(1L, "alice", InfluencerStatus.DISCOVERED, null, null);
        when(influencers.findByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED, PageRequest.of(0, 50, Sort.by("id"))))
                .thenReturn(new ArrayList<>(List.of(alice)));
        when(selector.currentSource()).thenReturn(RawSource.HIKER_MOBILE);
        Map<String, Object> item = hikerItem("alice", 12345L, "111");
        when(selector.fetchAndSupplement(JobName.QUALIFY, List.of("alice"), TriggerType.MANUAL))
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
        when(influencers.findByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED, PageRequest.of(0, 50, Sort.by("id"))))
                .thenReturn(new ArrayList<>(List.of(alice)));
        when(selector.currentSource()).thenReturn(RawSource.HIKER_MOBILE);
        when(selector.fetchAndSupplement(JobName.QUALIFY, List.of("alice"), TriggerType.MANUAL))
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
        when(influencers.findByStatusAndFollowersIsNotNull(InfluencerStatus.DISCOVERED))
                .thenReturn(List.of(inRange, outRange));

        var summary = job.run(TriggerType.MANUAL, false);

        assertThat(inRange.getStatus()).isEqualTo(InfluencerStatus.QUALIFIED);
        assertThat(outRange.getStatus()).isEqualTo(InfluencerStatus.EXCLUDED);
        assertThat(summary.qualified()).isEqualTo(1);
        assertThat(summary.excluded()).isEqualTo(1);
        assertThat(summary.deferred()).isEqualTo(0);
        // 이미 프로필 있음(lastProfiledAt != null) → 재수집 없음
        verify(selector, never()).fetchAndSupplement(any(), any(), any());
    }

    @Test
    void 프로필_응답에_해당_계정이_없으면_DISCOVERED_유지_deferred() {
        when(settings.qualifyBatchLimit()).thenReturn(50);
        Influencer bob = influencer(1L, "bob", InfluencerStatus.DISCOVERED, null, null);
        when(influencers.findByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED, PageRequest.of(0, 50, Sort.by("id"))))
                .thenReturn(new ArrayList<>(List.of(bob)));
        when(selector.currentSource()).thenReturn(RawSource.HIKER_MOBILE);
        when(selector.fetchAndSupplement(JobName.QUALIFY, List.of("bob"), TriggerType.MANUAL))
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
        when(influencers.findByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED, PageRequest.of(0, 50, Sort.by("id"))))
                .thenReturn(new ArrayList<>(List.of(bob)));
        when(selector.currentSource()).thenReturn(RawSource.SELF_GQL);
        when(selector.fetchAndSupplement(JobName.QUALIFY, List.of("bob"), TriggerType.MANUAL))
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
        when(influencers.findByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED, PageRequest.of(0, 50, Sort.by("id"))))
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
        verify(selector, never()).fetchAndSupplement(any(), any(), any());
    }
}
