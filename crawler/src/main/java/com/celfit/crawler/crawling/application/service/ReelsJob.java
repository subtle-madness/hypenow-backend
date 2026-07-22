package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.common.time.RevisitCutoff;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
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
 * last_reels_at을 북키핑한다(달력 기준 재방문 주기는 collect.revisit-interval-days 공유 — RevisitCutoff).
 * pk(ig_user_id) 없는 계정은 해석 요청을 쓰지 않고 스킵 — 프로필 수집이 pk를 채우면 다음
 * 실행에서 잡힌다. 즉 계정당 정확히 HikerAPI 1요청이다.
 */
@Service
public class ReelsJob {

    private static final Logger log = LoggerFactory.getLogger(ReelsJob.class);

    /** 릴스(클립)가 아예 없는 계정의 Hiker 404 표식 — 재시도 무의미, 수확 완료로 마킹한다. */
    static final String NO_CLIPS_MARK = "Entries not found";

    public record Summary(int visited, int postsUpserted, int skippedNoPk, int failedVisits) {}

    private final InfluencerRepository influencers;
    private final RawMediaPageRepository rawMediaPages;
    private final ContentUpserter contentUpserter;
    private final List<UserMediaPageFetcher> mediaFetchers;
    private final CrawlExecutor executor;
    private final SettingsService settings;
    private final Clock clock;
    private final JobProgress progress;
    private final JobStopFlag stopFlag;
    private final TransactionTemplate txTemplate;

    public ReelsJob(InfluencerRepository influencers, RawMediaPageRepository rawMediaPages,
                    ContentUpserter contentUpserter, List<UserMediaPageFetcher> mediaFetchers,
                    CrawlExecutor executor, SettingsService settings, Clock clock,
                    JobProgress progress, JobStopFlag stopFlag, TransactionTemplate txTemplate) {
        this.influencers = influencers;
        this.rawMediaPages = rawMediaPages;
        this.contentUpserter = contentUpserter;
        this.mediaFetchers = mediaFetchers;
        this.executor = executor;
        this.settings = settings;
        this.clock = clock;
        this.progress = progress;
        this.stopFlag = stopFlag;
        this.txTemplate = txTemplate;
    }

    /**
     * 방문(계정) 1회 = 트랜잭션 1개 — CollectJob과 동일한 이유(원형 보존 + HTTP 대기 중 커넥션
     * 미점유). RuntimeException은 해당 방문만 실패 처리하고 계속한다.
     */
    public Summary run(TriggerType trigger) {
        Instant revisitBefore = RevisitCutoff.boundary(clock, settings.revisitIntervalDays());
        List<Influencer> targets = influencers.findReelsTargets(
                revisitBefore, PageRequest.of(0, settings.reelsBatchLimit()));
        int visited = 0, upserted = 0, skippedNoPk = 0, failed = 0;
        progress.start(JobName.REELS, targets.size());
        try {
            for (Influencer inf : targets) {
                if (stopFlag.isRequested(JobName.REELS)) {
                    log.info("reels 중지 요청 — 잔여 방문 건너뛰고 조기 종료 ({}명 중 {}명 방문)",
                            targets.size(), visited + failed);
                    break;
                }
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
        // '릴스 없음' 404 판정은 콜백 안에서 빈 결과로 흡수한다 — 예외로 내보내면 CrawlExecutor가
        // run을 FAILED로 마감해, 양성 케이스가 실패 통계·어드민 FAILED 배지를 오염시킨다(07-22 실측:
        // run 실패의 ~95%가 이것). 요청은 나갔으므로 requestCount=1(과금 집계)은 유지한다.
        CrawlExecutor.Execution ex = executor.execute(JobName.REELS, trigger,
                null, inf.getUsername(), RawSource.HIKER_V2_CLIPS.name(), () -> {
                    try {
                        return new ApifyResult(null, 1, List.of(fetcher.fetchPage(inf.getIgUserId(), null)));
                    } catch (ApifyException e) {
                        if (e.getMessage() != null && e.getMessage().contains(NO_CLIPS_MARK)) {
                            return new ApifyResult(null, 1, List.of());
                        }
                        throw e;
                    }
                });
        if (ex.items().isEmpty()) {
            // 릴스가 아예 없는 계정 — 실패가 아니라 '수확할 것 없음' 확정. 재시도 루프 방지.
            inf.setLastReelsAt(clock.instant());
            influencers.save(inf);
            log.info("릴스 없음(404) — 수확 완료로 마킹: {}", inf.getUsername());
            return 0;
        }
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
