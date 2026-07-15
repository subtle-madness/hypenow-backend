package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawMediaPageRepository;
import com.celfit.crawler.crawling.application.port.out.UserMediaPageFetcher;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawMediaPage;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 릴스 수집 잡 — COLLECT(게시물을 위한 프로필 수집)에서 분리된 유료 구간. 뷰티 확정 계정마다
 * HikerAPI /v2/user/clips 1페이지를 수확해 raw_media_page 저장 + content upsert하고
 * last_reels_at을 북키핑한다(재방문 주기는 collect.revisit-interval-days 공유).
 * pk(ig_user_id) 없는 계정은 해석 요청을 쓰지 않고 스킵 — 프로필 수집이 pk를 채우면 다음
 * 실행에서 잡힌다. 즉 계정당 정확히 HikerAPI 1요청이다.
 */
@Service
public class ReelsJob {

    private static final Logger log = LoggerFactory.getLogger(ReelsJob.class);

    public record Summary(int visited, int postsUpserted, int skippedNoPk, int failedVisits) {}

    private final InfluencerRepository influencers;
    private final RawMediaPageRepository rawMediaPages;
    private final ContentUpserter contentUpserter;
    private final List<UserMediaPageFetcher> mediaFetchers;
    private final CrawlExecutor executor;
    private final SettingsService settings;
    private final Clock clock;
    private final JobProgress progress;
    private final TransactionTemplate txTemplate;

    public ReelsJob(InfluencerRepository influencers, RawMediaPageRepository rawMediaPages,
                    ContentUpserter contentUpserter, List<UserMediaPageFetcher> mediaFetchers,
                    CrawlExecutor executor, SettingsService settings, Clock clock,
                    JobProgress progress, TransactionTemplate txTemplate) {
        this.influencers = influencers;
        this.rawMediaPages = rawMediaPages;
        this.contentUpserter = contentUpserter;
        this.mediaFetchers = mediaFetchers;
        this.executor = executor;
        this.settings = settings;
        this.clock = clock;
        this.progress = progress;
        this.txTemplate = txTemplate;
    }

    /**
     * 방문(계정) 1회 = 트랜잭션 1개 — CollectJob과 동일한 이유(원형 보존 + HTTP 대기 중 커넥션
     * 미점유). RuntimeException은 해당 방문만 실패 처리하고 계속한다.
     */
    public Summary run(TriggerType trigger) {
        Instant revisitBefore = clock.instant().minus(Duration.ofDays(settings.revisitIntervalDays()));
        List<Influencer> targets = influencers.findReelsTargets(
                revisitBefore, PageRequest.of(0, settings.reelsBatchLimit()));
        int visited = 0, upserted = 0, skippedNoPk = 0, failed = 0;
        progress.start(JobName.REELS, targets.size());
        try {
            for (Influencer inf : targets) {
                if (inf.getIgUserId() == null || inf.getIgUserId().isBlank()) {
                    skippedNoPk++;   // 해석 요청 안 씀 — 프로필 수집이 pk를 채우면 다음 실행에서 잡힌다
                    log.warn("릴스 수집 스킵(pk 없음) — 프로필 수집 선행 필요: {}", inf.getUsername());
                    progress.advance(JobName.REELS, 1);
                    continue;
                }
                try {
                    upserted += txTemplate.execute(status -> visit(inf, trigger));
                    visited++;
                } catch (RuntimeException e) {
                    failed++;   // 계정 단위 실패(방문 트랜잭션 롤백) — 다음 실행 재시도
                    log.warn("릴스 방문 실패: {}", inf.getUsername(), e);
                } finally {
                    progress.advance(JobName.REELS, 1);
                }
            }
        } finally {
            progress.finish(JobName.REELS);
        }
        return new Summary(visited, upserted, skippedNoPk, failed);
    }

    /**
     * 방문 1회(트랜잭션 안): clips 1페이지 → raw 저장 → content upsert → last_reels_at 북키핑.
     * findReelsTargets가 트랜잭션 밖에서 조회돼 Influencer가 detached 상태다 — 명시 save 필수
     * (CollectJob 방문 북키핑과 동일 회귀 — CollectJobIntegrationTest 참고).
     */
    private int visit(Influencer inf, TriggerType trigger) {
        UserMediaPageFetcher fetcher = mediaFetchers.stream()
                .filter(f -> f.source() == RawSource.HIKER_V2_CLIPS).findFirst()
                .orElseThrow(() -> new IllegalStateException("HIKER_V2_CLIPS 페처 미등록"));
        CrawlExecutor.Execution ex = executor.execute(JobName.REELS, trigger,
                null, inf.getUsername(), RawSource.HIKER_V2_CLIPS.name(),
                () -> new ApifyResult(null, 1, List.of(fetcher.fetchPage(inf.getIgUserId(), null))));
        Map<String, Object> payload = ex.items().get(0);
        rawMediaPages.save(new RawMediaPage(inf.getId(), ex.runId(), RawSource.HIKER_V2_CLIPS,
                payload, clock.instant()));
        int upserted = contentUpserter.upsert(
                MediaItemExtractor.extract(payload, RawSource.HIKER_V2_CLIPS), inf);
        inf.setLastReelsAt(clock.instant());
        influencers.save(inf);
        return upserted;
    }
}
