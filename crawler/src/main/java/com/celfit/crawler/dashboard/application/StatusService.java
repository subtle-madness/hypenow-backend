package com.celfit.crawler.dashboard.application;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.ContentOrigin;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 상태 요약 — 대시보드 카드용. 파이프라인은 발굴(discover) → 판정(qualify) → 뷰티 판정(beauty)
 * → 수집(collect) 순서. 인플루언서는 판정 상태 3종 + 뷰티 판정 결과(뷰티/비뷰티/미판정) +
 * 수집 대기(뷰티만 — 미방문 + 재방문 주기 도래), 게시물은 방문 산출(origin=ENUMERATION) 총계와
 * 유형별(FEED/REELS)로 집계한다 — 댓글 수집이 꺼져 있어 상태 전이(PENDING→COLLECTED)는 화면
 * 지표가 아니다. 발굴 부산물(DISCOVERY)은 수집 대상이 아니므로 별도 보관 총계
 * (discoveryArchiveCount)로만 노출한다.
 */
@Service
public class StatusService {

    public record StatusSummary(Map<InfluencerStatus, Long> influencerByStatus,
                                 long beautyInfluencer,
                                 long beautyCompany,
                                 long beautyFalse,
                                 long beautyUnjudged,
                                 long backfillPending,
                                 long trackDue,
                                 long reelsDue,
                                 long enumeratedTotal,
                                 long enumeratedFeed,
                                 long enumeratedReels,
                                 long anyCollectedAccounts,
                                 long profileSnapshotAccounts,
                                 long reelsCollectedAccounts,
                                 long discoveryArchiveCount) {}

    private final InfluencerRepository influencers;
    private final ContentRepository contents;
    private final SettingsService settings;
    private final Clock clock;

    public StatusService(InfluencerRepository influencers, ContentRepository contents,
                         SettingsService settings, Clock clock) {
        this.influencers = influencers;
        this.contents = contents;
        this.settings = settings;
        this.clock = clock;
    }

    public StatusSummary summary() {
        Map<InfluencerStatus, Long> byInfluencerStatus = new EnumMap<>(InfluencerStatus.class);
        for (InfluencerStatus s : InfluencerStatus.values()) {
            byInfluencerStatus.put(s, influencers.countByStatus(s));
        }
        Instant revisitBefore = clock.instant().minus(Duration.ofDays(settings.revisitIntervalDays()));
        return new StatusSummary(byInfluencerStatus,
                influencers.countBeautyInfluencers(InfluencerStatus.QUALIFIED),
                influencers.countBeautyCompanies(InfluencerStatus.QUALIFIED),
                influencers.countByStatusAndBeauty(InfluencerStatus.QUALIFIED, false),
                influencers.countByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED),
                influencers.countBackfillPending(),
                influencers.countTrackDue(revisitBefore),
                influencers.countReelsDue(revisitBefore),
                contents.countByOrigin(ContentOrigin.ENUMERATION),
                contents.countByOriginAndContentType(ContentOrigin.ENUMERATION, ContentType.FEED),
                contents.countByOriginAndContentType(ContentOrigin.ENUMERATION, ContentType.REELS),
                influencers.countAnyCollected(),
                influencers.countByLastCollectedAtIsNotNull(),
                influencers.countByLastReelsAtIsNotNull(),
                contents.countByOrigin(ContentOrigin.DISCOVERY));
    }
}
