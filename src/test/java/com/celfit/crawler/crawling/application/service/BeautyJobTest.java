package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BeautyJobTest {

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    RawProfileRepository rawProfiles = mock(RawProfileRepository.class);
    BeautyJudge judge = mock(BeautyJudge.class);

    BeautyJob job = new BeautyJob(influencers, rawProfiles, judge);

    static Influencer qualified(Long id, String username) {
        Influencer inf = new Influencer(username);
        inf.setId(id);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        return inf;
    }

    static RawProfile legacyProfile(Long influencerId, String fullName, String bio) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fullName", fullName);
        payload.put("biography", bio);
        return new RawProfile(influencerId, null, RawSource.LEGACY_ENVELOPE, payload, Instant.EPOCH);
    }

    @Test
    void 판정_결과를_beauty_필드에_저장한다() {
        Influencer a = qualified(1L, "a");
        Influencer b = qualified(2L, "b");
        when(influencers.findByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED)).thenReturn(List.of(a, b));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "메이크업", "코덕")));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(2L))
                .thenReturn(Optional.of(legacyProfile(2L, "여행", "여행기")));
        when(judge.judge(any())).thenReturn(List.of(
                new BeautyJudge.Verdict("a", true, "메이크업 중심"),
                new BeautyJudge.Verdict("b", false, "여행 계정")));

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.judgedBeauty()).isEqualTo(1);
        assertThat(s.judgedNotBeauty()).isEqualTo(1);
        assertThat(a.getBeauty()).isTrue();
        assertThat(a.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
        assertThat(a.getBeautyReason()).isEqualTo("메이크업 중심");
        assertThat(b.getBeauty()).isFalse();
    }

    @Test
    void raw_profile이_없으면_스킵하고_beauty는_NULL_유지() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED)).thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L)).thenReturn(Optional.empty());

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.skippedNoProfile()).isEqualTo(1);
        assertThat(a.getBeauty()).isNull();
    }

    @Test
    void 배치_실패는_격리되고_해당_계정은_NULL로_남아_재시도된다() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED)).thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "x", "y")));
        when(judge.judge(any())).thenThrow(new ApifyException("CLI 실패"));

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.failedBatches()).isEqualTo(1);
        assertThat(a.getBeauty()).isNull();
    }

    @Test
    void 응답이_지어낸_username은_무시한다() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED)).thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "x", "y")));
        when(judge.judge(any())).thenReturn(List.of(new BeautyJudge.Verdict("ghost", true, "?")));

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.judgedBeauty()).isZero();
        assertThat(a.getBeauty()).isNull();
    }

    @Test
    void rejudge는_CLAUDE_판정분을_다시_포함하되_MANUAL은_선정하지_않는다() {
        when(influencers.findByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED)).thenReturn(List.of());
        when(influencers.findByStatusAndBeautySource(
                InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE)).thenReturn(List.of());

        job.run(TriggerType.MANUAL, true);

        verify(influencers).findByStatusAndBeautySource(
                InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE);
        // MANUAL 선정 쿼리는 존재하지 않음 — findByStatusAndBeautySource(…, "MANUAL") 호출 자체가 없다
    }
}
