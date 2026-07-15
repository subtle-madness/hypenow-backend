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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BeautyJobTest {

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    RawProfileRepository rawProfiles = mock(RawProfileRepository.class);
    BeautyJudge judge = mock(BeautyJudge.class);
    // 실객체 주입 — execute()가 콜백을 즉시 실행하므로 배치 단위 트랜잭션 래핑을 그대로 재현한다.
    TransactionTemplate txTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));

    BeautyJob job = new BeautyJob(influencers, rawProfiles, judge, txTemplate);

    @BeforeEach
    void wireSavePassthrough() {
        // 판정 적용이 이제 명시 save(detached merge)를 거치므로, 세터 결과 어서션이 save 이후에도
        // 그대로 성립하는지 확인하기 위한 passthrough.
        when(influencers.save(any(Influencer.class))).thenAnswer(inv -> inv.getArgument(0));
    }

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
    void 카드에_최근_캡션을_개수_제한과_길이_절단으로_담는다() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED)).thenReturn(List.of(a));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fullName", "이름");
        payload.put("latestPosts", List.of(
                Map.of("caption", "긴캡션".repeat(50)),  // 150자 → CAPTION_MAX_CHARS로 절단
                Map.of("caption", "둘"), Map.of("caption", "셋"), Map.of("caption", "넷"),
                Map.of("caption", "다섯"), Map.of("caption", "여섯"), Map.of("caption", "일곱")));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L)).thenReturn(Optional.of(
                new RawProfile(1L, null, RawSource.LEGACY_ENVELOPE, payload, Instant.EPOCH)));
        when(judge.judge(any())).thenAnswer(inv -> {
            List<BeautyJudge.ProfileCard> cards = inv.getArgument(0);
            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).captions()).containsExactly(
                    "긴캡션".repeat(50).substring(0, BeautyJob.CAPTION_MAX_CHARS), "둘", "셋", "넷", "다섯");
            return List.of(new BeautyJudge.Verdict("a", true, "ok"));
        });

        var s = job.run(TriggerType.MANUAL, false);

        assertThat(s.judgedBeauty()).isEqualTo(1);
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

    @Test
    void 두_선정_쿼리에_같은_인플루언서가_겹치면_한_번만_판정한다() {
        Influencer a = qualified(1L, "a");
        when(influencers.findByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED)).thenReturn(List.of(a));
        when(influencers.findByStatusAndBeautySource(
                InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE)).thenReturn(List.of(a));
        when(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(1L))
                .thenReturn(Optional.of(legacyProfile(1L, "메이크업", "코덕")));
        when(judge.judge(any())).thenAnswer(inv -> {
            List<BeautyJudge.ProfileCard> cards = inv.getArgument(0);
            assertThat(cards).hasSize(1);
            return List.of(new BeautyJudge.Verdict("a", true, "메이크업"));
        });

        var s = job.run(TriggerType.MANUAL, true);

        assertThat(s.judgedBeauty()).isEqualTo(1);
    }
}
