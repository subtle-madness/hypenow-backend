package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.common.config.CollectProperties;
import com.celfit.crawler.common.time.RevisitCutoff;
import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.application.service.ContentCaptionUpserter;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentOrigin;
import com.celfit.crawler.content.domain.ContentStatus;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.NotFoundException;
import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawCommentRepository;
import com.celfit.crawler.crawling.application.port.out.RawMediaPageRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.application.port.out.UserMediaPageFetcher;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawComment;
import com.celfit.crawler.crawling.domain.RawMediaPage;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 게시물을 위한 프로필 수집 잡 — 인플루언서를 방문해 프로필을 갱신하고, 프로필 원형에 내장된
 * 최근 피드(12개)를 content로 upsert한 뒤 대상 게시물의 댓글을 수집한다. 피드를 위한 별도
 * 요청은 없다 — 프로필 원형이 방문의 유일한 재료라, 프로필 갱신 실패(프록시 401 등)는 유료
 * 폴백 없이 방문 실패로 남겨 다음 실행에서 재시도한다(팔로워 추이 스냅샷 구멍 방지).
 * 릴스는 별도 REELS 잡(유료 HikerAPI 구간 분리 — ReelsJob). 기간 컷오프·페이지네이션 없이
 * 방문당 "최근 게시물 한 묶음"만 가져오며, 달력 기준 재방문 주기(collect.revisit-interval-days,
 * RevisitCutoff — 1이면 KST 자정에 전원 리셋)마다 다시 방문해 그 사이의 새 게시물을 잡는다.
 */
@Service
public class CollectJob {

    private static final Logger log = LoggerFactory.getLogger(CollectJob.class);

    /**
     * 동시 방문 수 — 프록시 로테이션(요청마다 새 exit IP) 전제, SelfProfileFetcher.FETCH_CONCURRENCY와
     * 동일 수준. 방문은 계정 단위로 독립(트랜잭션·북키핑 분리)이라 워커 간 공유 상태가 없다.
     */
    static final int VISIT_CONCURRENCY = 4;

    public record Summary(int visited, int postsUpserted, int postsCollected, int failedVisits) {}

    private final CollectProperties collectProps;
    private final InfluencerRepository influencers;
    private final RawProfileRepository rawProfiles;
    private final ContentRepository contents;
    private final ContentUpserter contentUpserter;
    private final ContentCaptionUpserter captionUpserter;
    private final RawCommentRepository rawComments;
    private final RawMediaPageRepository rawMediaPages;
    private final ProfileSourceSelector profileSourceSelector;
    private final CommentSourceSelector commentSource;
    private final List<UserMediaPageFetcher> mediaFetchers;
    private final CrawlExecutor executor;
    private final SettingsService settings;
    private final Clock clock;
    private final JobProgress progress;
    private final JobStopFlag stopFlag;
    private final TransactionTemplate txTemplate;

    public CollectJob(CollectProperties collectProps, InfluencerRepository influencers,
                      RawProfileRepository rawProfiles, ContentRepository contents,
                      ContentUpserter contentUpserter, ContentCaptionUpserter captionUpserter,
                      RawCommentRepository rawComments,
                      RawMediaPageRepository rawMediaPages,
                      ProfileSourceSelector profileSourceSelector, CommentSourceSelector commentSource,
                      List<UserMediaPageFetcher> mediaFetchers,
                      CrawlExecutor executor, SettingsService settings, Clock clock, JobProgress progress,
                      JobStopFlag stopFlag, TransactionTemplate txTemplate) {
        this.collectProps = collectProps;
        this.influencers = influencers;
        this.rawProfiles = rawProfiles;
        this.contents = contents;
        this.contentUpserter = contentUpserter;
        this.captionUpserter = captionUpserter;
        this.rawComments = rawComments;
        this.rawMediaPages = rawMediaPages;
        this.profileSourceSelector = profileSourceSelector;
        this.commentSource = commentSource;
        this.mediaFetchers = mediaFetchers;
        this.executor = executor;
        this.settings = settings;
        this.clock = clock;
        this.progress = progress;
        this.stopFlag = stopFlag;
        this.txTemplate = txTemplate;
    }

    /**
     * 배치 전체가 아니라 방문(인플루언서) 1회 = 트랜잭션 1개로 감싼다 — 원형 보존 원칙(방문 중 일부
     * 실패가 이미 저장된 다른 방문의 raw까지 롤백시키면 안 됨) + 커넥션을 HTTP 대기 내내 점유하지
     * 않기 위함. RuntimeException은 전부(ApifyException 포함) 해당 방문만 실패 처리하고 계속한다.
     *
     * 방문은 VISIT_CONCURRENCY 워커가 나눠 병렬 처리한다 — 계정 단위 독립이라 안전하고, 선정
     * 우선순위(백필 먼저)는 대상 리스트 순서로 근사 유지된다(워커가 앞에서부터 집어간다).
     */
    public Summary run(TriggerType trigger) {
        Instant revisitBefore = RevisitCutoff.boundary(clock, settings.revisitIntervalDays());
        List<Influencer> targets = influencers.findCollectTargets(
                revisitBefore, PageRequest.of(0, settings.collectBatchLimit()));
        var visited = new java.util.concurrent.atomic.AtomicInteger();
        var upserted = new java.util.concurrent.atomic.AtomicInteger();
        var collected = new java.util.concurrent.atomic.AtomicInteger();
        var failed = new java.util.concurrent.atomic.AtomicInteger();
        var next = new java.util.concurrent.atomic.AtomicInteger();
        progress.start(JobName.COLLECT, targets.size());
        try {
            if (targets.size() <= 1) {   // 소량은 풀 없이 처리
                if (!targets.isEmpty()) visitOne(targets.get(0), trigger, visited, upserted, collected, failed);
            } else {
                // close()가 제출된 작업 완료까지 대기(Java 21) — SelfProfileFetcher와 동일 관용구
                try (var pool = java.util.concurrent.Executors.newFixedThreadPool(VISIT_CONCURRENCY)) {
                    for (int w = 0; w < VISIT_CONCURRENCY; w++) {
                        pool.submit(() -> {
                            int i;
                            // 중지 요청 시 워커가 새 방문을 집지 않는다 — 진행 중 방문은 끝까지 수행
                            while (!stopFlag.isRequested(JobName.COLLECT)
                                    && (i = next.getAndIncrement()) < targets.size()) {
                                visitOne(targets.get(i), trigger, visited, upserted, collected, failed);
                            }
                        });
                    }
                }
            }
            if (stopFlag.isRequested(JobName.COLLECT)) {
                log.info("collect 중지 요청 — 잔여 방문 건너뛰고 조기 종료 ({}명 중 {}명 방문)",
                        targets.size(), visited.get() + failed.get());
            }
        } finally {
            progress.finish(JobName.COLLECT);
        }
        return new Summary(visited.get(), upserted.get(), collected.get(), failed.get());
    }

    /** 방문 1회 — 결과·실패를 공유 카운터에 집계한다. 예외 격리 의미는 순차 시절과 동일. */
    private void visitOne(Influencer inf, TriggerType trigger,
                          java.util.concurrent.atomic.AtomicInteger visited,
                          java.util.concurrent.atomic.AtomicInteger upserted,
                          java.util.concurrent.atomic.AtomicInteger collected,
                          java.util.concurrent.atomic.AtomicInteger failed) {
        try {
            VisitResult r = txTemplate.execute(status -> visit(inf, trigger));
            upserted.addAndGet(r.upserted());
            collected.addAndGet(r.collected());
            visited.incrementAndGet();
        } catch (NotFoundException e) {
            // 계정 소멸(404) — 방문 실패(재시도)가 아니라 소프트 딜리트로 종결.
            // 방문 트랜잭션은 롤백됐으므로 여기(밖)서 저장해야 확정된다.
            inf.setStatus(InfluencerStatus.DELETED);
            influencers.save(inf);
            log.info("collect 계정 소멸(404) — DELETED: {}", inf.getUsername());
        } catch (RuntimeException e) {
            failed.incrementAndGet();   // 인플루언서 단위 실패(방문 트랜잭션 롤백) — 다음 실행 재시도
            log.warn("collect 방문 실패: {}", inf.getUsername(), e);
        } finally {
            progress.advance(JobName.COLLECT, 1);
        }
    }

    private record VisitResult(int upserted, int collected) {}

    private VisitResult visit(Influencer inf, TriggerType trigger) {
        // 1) 프로필 갱신 (원형 저장 + followers·userId 추출) — 방문의 유일한 재료. 실패는
        // ApifyException으로 방문 실패(재시도) 처리되며 유료 피드 폴백은 없다.
        Map<String, Object> payload = refreshProfile(inf, trigger);

        // 2) 피드: 프로필 원형에 내장된 최근 12개(SELF·WebGQL) — 내장 타임라인이 없는 소스
        // (HIKER_MOBILE 등)는 /v1/user/medias/chunk 1페이지로 보충한다. SELF 실패의 유료 폴백이
        // 아니라(그건 방문 실패·재시도 유지) 소스가 원래 타임라인을 안 주는 경우의 열거 경로다.
        // 소스를 지역 변수로 고정한다 — 캡션 provenance로 아래에서 다시 쓴다.
        boolean hasEmbedded = MediaItemExtractor.hasEmbeddedTimeline(payload);
        RawSource feedSource = hasEmbedded ? RawSource.SELF_GQL : RawSource.HIKER_V1_MEDIAS;
        Map<String, MediaItemExtractor.MediaItem> inWindow = new LinkedHashMap<>();
        if (hasEmbedded) {
            for (var it : MediaItemExtractor.extract(payload, RawSource.SELF_GQL)) {
                inWindow.putIfAbsent(it.shortCode(), it);
            }
        } else {
            supplementFeedPage(inf, trigger, inWindow);
        }

        // 3) content upsert — 신규 생성·DISCOVERY 승격은 ContentUpserter(REELS 잡과 공유) 규칙.
        // 릴스 1페이지는 별도 REELS 잡으로 분리됐다(유료 HikerAPI 구간).
        int upserted = contentUpserter.upsert(inWindow.values(), inf);
        // 캡션은 content 행이 생긴 뒤에 적재한다(content_id FK).
        captionUpserter.upsert(inWindow.values(), feedSource, clock.instant());

        // 4) 게시물별 댓글 수집 — 현재는 불필요해 기본 꺼짐(crawler.collect.comments-enabled, yml 전용).
        // 나중에 다시 필요할 수 있어 로직은 유지한다. 켜면: 이번 열거분이 아니라 이 인플루언서의
        // ENUMERATION PENDING 전체가 대상이고, collect_attempts 상한(maxAttempts→FAILED)이 폭주를 막는다.
        int collected = 0;
        if (collectProps.commentsEnabled()) {
            List<Content> pending = contents.findByInfluencerIdAndStatusAndOrigin(
                    inf.getId(), ContentStatus.PENDING, ContentOrigin.ENUMERATION);
            collected = collectComments(pending, trigger);
        }

        // firstCollectedAt은 "첫 방문 완료" 표식 — 댓글 실패와 무관하게 열거 성공 시 기록한다.
        // 실패 게시물의 재시도는 위 PENDING 조회가 담당하므로 별도 신호가 필요 없다.
        if (inf.getFirstCollectedAt() == null) inf.setFirstCollectedAt(clock.instant());
        inf.setLastCollectedAt(clock.instant());
        // findCollectTargets는 방문 트랜잭션 밖(리포지토리 자체 트랜잭션)에서 조회돼 detached 상태다 —
        // 명시적 save(merge) 없이는 위 북키핑(방문 시각·followers)이 저장되지 않아 같은 인플루언서가
        // 매 실행 재선정(백필 무한 반복·재과금)된다.
        influencers.save(inf);
        return new VisitResult(upserted, collected);
    }

    /**
     * 내장 타임라인이 없는 프로필 소스의 피드 열거 — HIKER_V1_MEDIAS(모바일 계열) 1페이지를
     * 수확해 raw 저장 + inWindow에 합류. 페처 미등록·pk 부재면 게시물 0건으로 프로필만 갱신
     * (pk는 이번 프로필 갱신이 채웠을 수 있어 다음 방문부터 잡힌다). 요청 실패는 ApifyException
     * → 방문 실패·재시도(피드 없는 "방문 완료"를 만들지 않는다 — 2026-07-18 HIKER_MOBILE 사고 재발 방지).
     */
    private void supplementFeedPage(Influencer inf, TriggerType trigger,
                                    Map<String, MediaItemExtractor.MediaItem> inWindow) {
        UserMediaPageFetcher fetcher = mediaFetchers.stream()
                .filter(f -> f.source() == RawSource.HIKER_V1_MEDIAS).findFirst().orElse(null);
        if (fetcher == null || inf.getIgUserId() == null || inf.getIgUserId().isBlank()) {
            log.warn("피드 보충 스킵(페처 없음 또는 pk 없음) — 프로필만 갱신: {}", inf.getUsername());
            return;
        }
        CrawlExecutor.Execution ex = executor.execute(JobName.COLLECT, trigger,
                null, inf.getUsername(), RawSource.HIKER_V1_MEDIAS.name(),
                () -> new ApifyResult(null, 1, List.of(fetcher.fetchPage(inf.getIgUserId(), null))));
        Map<String, Object> page = ex.items().get(0);
        rawMediaPages.save(new RawMediaPage(inf.getId(), ex.runId(), RawSource.HIKER_V1_MEDIAS,
                page, clock.instant()));
        for (var it : MediaItemExtractor.extract(page, RawSource.HIKER_V1_MEDIAS)) {
            inWindow.putIfAbsent(it.shortCode(), it);
        }
    }

    /**
     * 프로필 원형 저장 + followers·igUserId 갱신 후 원형(payload)을 반환한다.
     * 프로필이 방문의 유일한 재료이므로 실패(프록시 간헐 401·응답 누락·userId 추출 실패)는
     * 전부 ApifyException — 호출자(run 루프)가 방문 실패로 격리하고 다음 실행에서 재시도한다.
     */
    private Map<String, Object> refreshProfile(Influencer inf, TriggerType trigger) {
        RawSource batchSource = profileSourceSelector.currentSource();
        CrawlExecutor.Execution ex = profileSourceSelector.fetchAndSupplement(
                JobName.COLLECT, List.of(inf.getUsername()), trigger);
        if (ex.notFound().contains(inf.getUsername())) {
            // 방문 트랜잭션 안에서 저장하면 이 예외로 롤백된다 — run 루프가 트랜잭션 밖에서 처리
            throw new NotFoundException("프로필 404 — 계정 소멸: " + inf.getUsername());
        }
        for (Map<String, Object> item : ex.items()) {
            // 컴포지트(400 → Hiker 폴백) 배치는 아이템별 원형이 섞인다 — 셰이프로 실제 소스 감지
            RawSource source = ProfileExtractor.detect(item, batchSource);
            String username = ProfileExtractor.username(item, source);
            if (username == null || !username.equals(inf.getUsername())) continue;
            RawProfile rp = new RawProfile(inf.getId(), ex.runId(), source, item, clock.instant());
            rp.setUsername(username);
            Long followers = ProfileExtractor.followers(item, source);
            rp.setFollowers(followers);
            rawProfiles.save(rp);
            inf.setFollowers(followers);
            inf.setLastProfiledAt(clock.instant());
            String userId = ProfileExtractor.userId(item, source);
            if (userId == null) {
                throw new ApifyException("프로필 userId 추출 실패 — 방문 재시도: " + inf.getUsername());
            }
            inf.setIgUserId(userId);   // REELS 잡의 pk 재료 — 백필
            return item;
        }
        throw new ApifyException("프로필 응답에 계정 없음(401 차단 등) — 방문 재시도: " + inf.getUsername());
    }

    private int collectComments(List<Content> pending, TriggerType trigger) {
        if (pending.isEmpty()) return 0;
        List<String> codes = pending.stream().map(Content::getShortCode).toList();
        CommentFetcher.CommentResult r;
        RawSource source = commentSource.currentSource();
        try {
            r = commentSource.current().fetch(codes, settings.commentsPerPost(), trigger);
        } catch (ApifyException e) {
            bumpAttempts(pending);
            return 0;
        }
        int collected = 0;
        for (Content c : pending) {
            List<Map<String, Object>> pages = r.pagesByCode().get(c.getShortCode());
            if (pages == null) {
                bumpAttempts(List.of(c));
                continue;
            }
            for (Map<String, Object> pagePayload : pages) {
                rawComments.save(new RawComment(c.getId(), r.runId(), source, pagePayload, clock.instant()));
            }
            c.setStatus(ContentStatus.COLLECTED);
            c.setCollectedAt(clock.instant());
            collected++;
        }
        return collected;
    }

    private void bumpAttempts(List<Content> chunk) {
        for (Content c : chunk) {
            c.setCollectAttempts(c.getCollectAttempts() + 1);
            if (c.getCollectAttempts() >= settings.maxAttempts()) c.setStatus(ContentStatus.FAILED);
        }
    }
}
