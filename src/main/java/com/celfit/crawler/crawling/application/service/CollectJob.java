package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentOrigin;
import com.celfit.crawler.content.domain.ContentStatus;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawCommentRepository;
import com.celfit.crawler.crawling.application.port.out.RawMediaPageRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.application.port.out.UserMediaPageFetcher;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawComment;
import com.celfit.crawler.crawling.domain.RawMediaPage;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.time.Duration;
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
 * 수집 잡 — 인플루언서를 방문해 프로필을 갱신하고, 프로필 원형에 내장된 최근 피드(12개)와
 * 릴스 1페이지를 content로 upsert한 뒤 대상 게시물의 댓글을 수집한다. 기간 컷오프·페이지네이션
 * 없이 방문당 "최근 게시물 한 묶음"만 가져오며, 재방문 주기(collect.revisit-interval-days)마다
 * 다시 방문해 그 사이의 새 게시물을 잡는다.
 */
@Service
public class CollectJob {

    private static final Logger log = LoggerFactory.getLogger(CollectJob.class);

    public record Summary(int visited, int postsUpserted, int postsCollected, int failedVisits) {}

    private final InfluencerRepository influencers;
    private final RawProfileRepository rawProfiles;
    private final RawMediaPageRepository rawMediaPages;
    private final ContentRepository contents;
    private final RawCommentRepository rawComments;
    private final List<UserMediaPageFetcher> mediaFetchers;
    private final ProfileSourceSelector profileSourceSelector;
    private final CommentSourceSelector commentSource;
    private final CrawlExecutor executor;
    private final SettingsService settings;
    private final Clock clock;
    private final JobProgress progress;
    private final TransactionTemplate txTemplate;

    public CollectJob(InfluencerRepository influencers, RawProfileRepository rawProfiles,
                      RawMediaPageRepository rawMediaPages, ContentRepository contents,
                      RawCommentRepository rawComments, List<UserMediaPageFetcher> mediaFetchers,
                      ProfileSourceSelector profileSourceSelector, CommentSourceSelector commentSource,
                      CrawlExecutor executor, SettingsService settings, Clock clock, JobProgress progress,
                      TransactionTemplate txTemplate) {
        this.influencers = influencers;
        this.rawProfiles = rawProfiles;
        this.rawMediaPages = rawMediaPages;
        this.contents = contents;
        this.rawComments = rawComments;
        this.mediaFetchers = mediaFetchers;
        this.profileSourceSelector = profileSourceSelector;
        this.commentSource = commentSource;
        this.executor = executor;
        this.settings = settings;
        this.clock = clock;
        this.progress = progress;
        this.txTemplate = txTemplate;
    }

    /**
     * 배치 전체가 아니라 방문(인플루언서) 1회 = 트랜잭션 1개로 감싼다 — 원형 보존 원칙(방문 중 일부
     * 실패가 이미 저장된 다른 방문의 raw까지 롤백시키면 안 됨) + 커넥션을 HTTP 대기 내내 점유하지
     * 않기 위함. RuntimeException은 전부(ApifyException 포함) 해당 방문만 실패 처리하고 계속한다.
     */
    public Summary run(TriggerType trigger) {
        Instant revisitBefore = clock.instant().minus(Duration.ofDays(settings.revisitIntervalDays()));
        List<Influencer> targets = influencers.findCollectTargets(
                revisitBefore, PageRequest.of(0, settings.collectBatchLimit()));
        int visited = 0, upserted = 0, collected = 0, failed = 0;
        progress.start(JobName.COLLECT, targets.size());
        try {
            for (Influencer inf : targets) {
                try {
                    VisitResult r = txTemplate.execute(status -> visit(inf, trigger));
                    upserted += r.upserted();
                    collected += r.collected();
                    visited++;
                } catch (RuntimeException e) {
                    failed++;   // 인플루언서 단위 실패(방문 트랜잭션 롤백) — 다음 실행 재시도
                    log.warn("collect 방문 실패: {}", inf.getUsername(), e);
                } finally {
                    progress.advance(JobName.COLLECT, 1);
                }
            }
        } finally {
            progress.finish(JobName.COLLECT);
        }
        return new Summary(visited, upserted, collected, failed);
    }

    private record VisitResult(int upserted, int collected) {}

    private VisitResult visit(Influencer inf, TriggerType trigger) {
        // 1) 프로필 갱신 (원형 저장 + followers·userId 추출) — SELF 원형에는 최근 피드 12개가
        // 내장돼 있어 피드 열거를 겸한다(별도 유료 요청 불필요).
        ProfileRefresh profile = refreshProfile(inf, trigger);

        // 2) 피드: 내장 타임라인 우선, 없으면(비 SELF 소스·프로필 미확보) 피드 1페이지 폴백
        Map<String, MediaItemExtractor.MediaItem> inWindow = new LinkedHashMap<>();
        if (profile.payload() != null && MediaItemExtractor.hasEmbeddedTimeline(profile.payload())) {
            for (var it : MediaItemExtractor.extract(profile.payload(), RawSource.SELF_GQL)) {
                inWindow.putIfAbsent(it.shortCode(), it);
            }
        } else {
            fetchOnePage(inf, RawSource.HIKER_GQL_MEDIAS, profile.userId(), trigger, inWindow);
        }

        // 3) 릴스 1페이지 — 내장 타임라인(피드 그리드)에 안 잡히는 릴스 커버 (shortCode dedup)
        fetchOnePage(inf, RawSource.HIKER_V2_CLIPS, profile.userId(), trigger, inWindow);

        // 4) content upsert — 신규는 ENUMERATION(수집 대상)으로 생성. 기존 행이 발굴 부산물(DISCOVERY)
        // 이었다면 이번 열거로 정식 수집 범위에 들어온 것이므로 ENUMERATION으로 승격한다.
        int upserted = 0;
        for (var item : inWindow.values()) {
            Content existing = contents.findByShortCode(item.shortCode()).orElse(null);
            if (existing == null) {
                contents.save(new Content(item.shortCode(), item.type(),
                        inf.getUsername(), inf.getId(), item.takenAt(), clock.instant(), ContentOrigin.ENUMERATION));
            } else if (existing.getOrigin() == ContentOrigin.DISCOVERY) {
                existing.setOrigin(ContentOrigin.ENUMERATION);
            }
            upserted++;
        }

        // 5) 게시물별 댓글 수집 — 이번 열거 윈도우가 아니라 이 인플루언서의 PENDING 전체가 대상이다.
        // 백필 중 댓글만 실패한 게시물이나 discover가 만든 오래된 PENDING도 track-window 컷오프와
        // 무관하게 매 방문 재시도된다. collect_attempts 상한(maxAttempts→FAILED)이 폭주를 막는다.
        // origin=ENUMERATION만 대상 — 발굴 부산물(DISCOVERY)은 수집 범위 밖(위 upsert가 방문 범위
        // 안이면 승격시키므로 유실 없음).
        List<Content> pending = contents.findByInfluencerIdAndStatusAndOrigin(
                inf.getId(), ContentStatus.PENDING, ContentOrigin.ENUMERATION);
        int collected = collectComments(pending, trigger);

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

    /** 방문의 프로필 갱신 결과 — payload는 원형(내장 타임라인 추출용), 갱신 실패 폴백이면 null. */
    private record ProfileRefresh(String userId, Map<String, Object> payload) {}

    /**
     * 프로필 원형 저장 + followers·igUserId 갱신 후 열거에 쓸 userId와 원형을 반환한다.
     * 프로필 갱신은 프록시 간헐 401 등으로 언제든 실패할 수 있으므로 방문의 전제가 아니다 —
     * 실패해도 저장된 igUserId(판정·이전 방문 때 추출)가 있으면 그 값으로 열거를 계속하고,
     * 그것마저 없을 때만 방문 실패로 던진다.
     */
    private ProfileRefresh refreshProfile(Influencer inf, TriggerType trigger) {
        RawSource source = profileSourceSelector.currentSource();
        String failure = null;
        try {
            CrawlExecutor.Execution ex = profileSourceSelector.fetchAndSupplement(List.of(inf.getUsername()), trigger);
            for (Map<String, Object> item : ex.items()) {
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
                if (userId != null) {
                    inf.setIgUserId(userId);
                    return new ProfileRefresh(userId, item);
                }
                failure = "userId 추출 실패";
            }
            if (failure == null) failure = "응답에 계정 없음(401 차단 등)";
        } catch (ApifyException e) {
            failure = "프로필 요청 실패: " + e.getMessage();
        }
        String stored = inf.getIgUserId();
        if (stored == null) {
            throw new ApifyException("프로필 확보 실패(저장된 userId도 없음) — " + failure + ": " + inf.getUsername());
        }
        log.warn("collect 프로필 갱신 실패({}) — 저장된 userId로 열거 계속: {}", failure, inf.getUsername());
        return new ProfileRefresh(stored, null);
    }

    /** 해당 소스의 첫 페이지만 — 원형 저장 후 추출해 sink에 합류(shortCode dedup). 페이지네이션 없음. */
    private void fetchOnePage(Influencer inf, RawSource source, String userId, TriggerType trigger,
                              Map<String, MediaItemExtractor.MediaItem> sink) {
        UserMediaPageFetcher fetcher = mediaFetchers.stream()
                .filter(f -> f.source() == source).findFirst().orElse(null);
        if (fetcher == null) return;
        CrawlExecutor.Execution ex = executor.execute(JobName.COLLECT, trigger,
                null, inf.getUsername(), source.name(),
                () -> new ApifyResult(null, 1, List.of(fetcher.fetchPage(userId, null))));
        Map<String, Object> payload = ex.items().get(0);
        rawMediaPages.save(new RawMediaPage(inf.getId(), ex.runId(), source, payload, clock.instant()));
        for (var it : MediaItemExtractor.extract(payload, source)) {
            sink.putIfAbsent(it.shortCode(), it);
        }
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
