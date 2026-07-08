package com.celfit.crawler.admin;

import com.celfit.crawler.domain.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class StatusService {

    public record StatusSummary(Map<ContentStatus, Long> contentByStatus,
                                long rawDiscoveryPosts, long rawPostDetails,
                                long rawComments, long rawProfiles, long dueForAggregate) {}

    private final ContentRepository contents;
    private final RawDiscoveryPostRepository rawDiscovery;
    private final RawPostDetailRepository rawDetails;
    private final RawCommentRepository rawComments;
    private final RawProfileRepository rawProfiles;
    private final SettingsService settings;
    private final Clock clock;

    public StatusService(ContentRepository contents, RawDiscoveryPostRepository rawDiscovery,
                         RawPostDetailRepository rawDetails, RawCommentRepository rawComments,
                         RawProfileRepository rawProfiles, SettingsService settings,
                         Clock clock) {
        this.contents = contents;
        this.rawDiscovery = rawDiscovery;
        this.rawDetails = rawDetails;
        this.rawComments = rawComments;
        this.rawProfiles = rawProfiles;
        this.settings = settings;
        this.clock = clock;
    }

    public StatusSummary summary() {
        Map<ContentStatus, Long> byStatus = new EnumMap<>(ContentStatus.class);
        for (ContentStatus s : ContentStatus.values()) {
            byStatus.put(s, contents.countByStatus(s));
        }
        Instant cutoff = clock.instant().minus(Duration.ofDays(settings.delayDays()));
        long due = contents.countByStatusAndAggregatedAtIsNullAndUploadedAtLessThanEqual(
                ContentStatus.QUALIFIED, cutoff);
        return new StatusSummary(byStatus, rawDiscovery.count(), rawDetails.count(),
                rawComments.count(), rawProfiles.count(), due);
    }
}
