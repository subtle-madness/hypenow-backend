package com.celfit.crawler.dashboard.application;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.ContentOrigin;
import com.celfit.crawler.content.domain.ContentStatus;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 상태 요약 — 대시보드 카드용. 파이프라인은 발굴(discover) → 판정(qualify) → 수집(collect) 순서.
 * 인플루언서는 판정 상태 3종 + 백필 대기(판정 통과했지만 첫 수집 전), 게시물은 수집 상태 3종
 * — collect 열거(origin=ENUMERATION) 기준으로만 집계한다. 발굴 부산물(DISCOVERY)은 수집 대상이
 * 아니므로 별도 보관 총계(discoveryArchiveCount)로만 노출한다.
 */
@Service
public class StatusService {

    public record StatusSummary(Map<InfluencerStatus, Long> influencerByStatus,
                                 long backfillPending,
                                 Map<ContentStatus, Long> contentByStatus,
                                 long discoveryArchiveCount) {}

    private final InfluencerRepository influencers;
    private final ContentRepository contents;

    public StatusService(InfluencerRepository influencers, ContentRepository contents) {
        this.influencers = influencers;
        this.contents = contents;
    }

    public StatusSummary summary() {
        Map<InfluencerStatus, Long> byInfluencerStatus = new EnumMap<>(InfluencerStatus.class);
        for (InfluencerStatus s : InfluencerStatus.values()) {
            byInfluencerStatus.put(s, influencers.countByStatus(s));
        }
        Map<ContentStatus, Long> byContentStatus = new EnumMap<>(ContentStatus.class);
        for (ContentStatus s : ContentStatus.values()) {
            byContentStatus.put(s, contents.countByStatusAndOrigin(s, ContentOrigin.ENUMERATION));
        }
        return new StatusSummary(byInfluencerStatus, influencers.countBackfillPending(), byContentStatus,
                contents.countByOrigin(ContentOrigin.DISCOVERY));
    }
}
