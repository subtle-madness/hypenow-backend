package com.celfit.crawler.dashboard.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
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

        verify(influencers).countTrackDue(Instant.parse("2026-07-17T15:00:00Z"));
        verify(influencers).countReelsDue(Instant.parse("2026-07-17T15:00:00Z"));
    }
}
