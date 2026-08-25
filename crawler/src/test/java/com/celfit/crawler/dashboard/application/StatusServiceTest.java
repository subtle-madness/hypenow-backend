package com.celfit.crawler.dashboard.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.CategoryClass;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class StatusServiceTest {

    // 2026-07-18 10:00 KST — 존이 KST인 클록에서 "오늘 자정"은 KST 자정이어야 한다
    static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T01:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final InfluencerRepository influencers = mock(InfluencerRepository.class);
    private final ContentRepository contents = mock(ContentRepository.class);
    private final SettingsService settings = mock(SettingsService.class);

    private final StatusService service = new StatusService(influencers, contents, settings, CLOCK);

    @Test
    void trackDue_reelsDue_카드는_달력일_기준_경계로_센다() {
        // 주기 1일 → 경계 = 오늘(KST) 자정 = 2026-07-17T15:00Z. 어제 방문 계정 전부가 due로 잡혀야
        // 데일리 대시보드의 "잔여"와 잡 선정 대상이 같은 숫자가 된다.
        when(settings.revisitIntervalDays()).thenReturn(1);

        service.summary();

        verify(influencers).countTrackDue(Instant.parse("2026-07-17T15:00:00Z"), false);
        verify(influencers).countReelsDue(Instant.parse("2026-07-17T15:00:00Z"), false);
    }

    @Test
    void 대기열_타일은_F앤B_토글을_그대로_모수에_반영한다() {
        // 토글 on이면 대기열 타일이 선정 쿼리와 같은 모수(F&B 포함)를 보여준다 — 스펙 2026-08-23 §4.
        when(settings.revisitIntervalDays()).thenReturn(1);
        when(settings.fnbPipelineEnabled()).thenReturn(true);

        service.summary();

        verify(influencers).countBackfillPending(true);
        verify(influencers).countTrackDue(Instant.parse("2026-07-17T15:00:00Z"), true);
        verify(influencers).countReelsDue(Instant.parse("2026-07-17T15:00:00Z"), true);
    }

    @Test
    void 상태판은_F앤B_분류별_카운트를_fnb_class_기준으로_센다() {
        // 뷰티 그룹과 대칭인 5분류 타일 — 회사/서비스/외국인/아님은 fnb_class 단일 기준으로 센다
        when(settings.revisitIntervalDays()).thenReturn(1);
        when(influencers.countByStatusAndFnbClass(InfluencerStatus.QUALIFIED, CategoryClass.COMPANY))
                .thenReturn(3L);
        when(influencers.countByStatusAndFnbClass(InfluencerStatus.QUALIFIED, CategoryClass.SERVICE))
                .thenReturn(2L);
        when(influencers.countByStatusAndFnbClass(InfluencerStatus.QUALIFIED, CategoryClass.FOREIGN_INFLUENCER))
                .thenReturn(1L);
        when(influencers.countByStatusAndFnbClass(InfluencerStatus.QUALIFIED, CategoryClass.NONE))
                .thenReturn(5L);

        StatusService.StatusSummary s = service.summary();

        assertThat(s.fnbCompany()).isEqualTo(3);
        assertThat(s.fnbService()).isEqualTo(2);
        assertThat(s.fnbForeign()).isEqualTo(1);
        assertThat(s.fnbNone()).isEqualTo(5);
    }
}
