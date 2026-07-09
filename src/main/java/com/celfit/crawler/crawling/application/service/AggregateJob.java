package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.domain.ShortCodes;
import com.celfit.crawler.crawling.domain.*;
import com.celfit.crawler.content.domain.*;
import com.celfit.crawler.settings.domain.*;
import com.celfit.crawler.crawling.application.port.out.*;
import com.celfit.crawler.content.application.port.out.*;
import com.celfit.crawler.settings.application.port.out.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AggregateJob {

    public record Summary(int aggregated, int gone, int retried, int failed) {}

    private final ContentRepository contents;
    private final RawPostDetailRepository rawDetails;
    private final RawCommentRepository rawComments;
    private final CrawlExecutor executor;
    private final SettingsService settings;
    private final Clock clock;
    private final CommentSourceSelector commentSource;

    public AggregateJob(ContentRepository contents, RawPostDetailRepository rawDetails,
                        RawCommentRepository rawComments, CrawlExecutor executor,
                        SettingsService settings, Clock clock, CommentSourceSelector commentSource) {
        this.contents = contents;
        this.rawDetails = rawDetails;
        this.rawComments = rawComments;
        this.executor = executor;
        this.settings = settings;
        this.clock = clock;
        this.commentSource = commentSource;
    }

    @Transactional
    public Summary run(TriggerType trigger) {
        Instant cutoff = clock.instant().minus(Duration.ofDays(settings.delayDays()));
        List<Content> due = contents.findDue(ContentStatus.QUALIFIED, cutoff,
                PageRequest.of(0, settings.batchLimit()));

        // 유형별 전용 상세 액터 — 릴스/피드가 주는 필드가 달라 나눠 호출한다
        int aggregated = 0, gone = 0, retried = 0, failed = 0;
        for (ContentType type : ContentType.values()) {
            List<Content> group = due.stream().filter(c -> c.getContentType() == type).toList();
            for (List<Content> chunk : ActorInputs.chunk(group, settings.chunkSize())) {
                ChunkResult r = aggregateChunk(chunk, type, trigger);
                aggregated += r.aggregated;
                gone += r.gone;
                retried += r.retried;
                failed += r.failed;
            }
        }
        return new Summary(aggregated, gone, retried, failed);
    }

    private record ChunkResult(int aggregated, int gone, int retried, int failed) {}

    private ChunkResult aggregateChunk(List<Content> chunk, ContentType type, TriggerType trigger) {
        List<String> detailUrls = chunk.stream()
                .map(c -> type == ContentType.REELS
                        ? ShortCodes.reelUrl(c.getShortCode())
                        : ShortCodes.postUrl(c.getShortCode()))
                .toList();
        String detailActor = type == ContentType.REELS ? Actors.DETAIL_REELS : Actors.DETAIL_FEED;

        Map<String, Map<String, Object>> detailByCode;
        Map<String, List<Map<String, Object>>> commentsByCode;
        Long detailRunId;
        Long commentRunId;
        try {
            var dx = executor.execute(JobName.AGGREGATE, trigger, null, null,
                    detailActor, ActorInputs.detailUrls(detailUrls));
            detailRunId = dx.runId();
            detailByCode = indexDetails(dx.items());
            if (detailByCode.isEmpty() && !chunk.isEmpty()) {
                // 요청 전부가 응답에 없음 = 삭제가 아니라 액터 소프트 실패(레이트리밋 등) 가능성
                // — mass-GONE 대신 재시도. 댓글 액터 호출도 아낀다.
                int f = bumpAttempts(chunk);
                return new ChunkResult(0, 0, chunk.size() - f, f);
            }
            List<String> shortCodes = chunk.stream().map(Content::getShortCode).toList();
            var cx = commentSource.current()
                    .fetch(shortCodes, settings.commentsPerPost(), trigger);
            commentRunId = cx.runId();
            commentsByCode = groupComments(cx.items());
        } catch (ApifyException e) {
            // 청크 전체 재시도 대상
            int f = bumpAttempts(chunk);
            return new ChunkResult(0, 0, chunk.size() - f, f);
        }

        int aggregated = 0, gone = 0;
        for (Content c : chunk) {
            Map<String, Object> detail = detailByCode.get(c.getShortCode());
            if (detail == null) {
                c.setStatus(ContentStatus.GONE);  // 응답에 없음 = 삭제·비공개 간주
                gone++;
                continue;
            }
            rawDetails.save(new RawPostDetail(c.getId(), detailRunId, detail, clock.instant()));
            for (Map<String, Object> comment : commentsByCode.getOrDefault(c.getShortCode(), List.of())) {
                rawComments.save(new RawComment(c.getId(), commentRunId, comment, clock.instant()));
            }
            c.setAdMarked(AdSignals.adMarked(detail));
            c.setStatus(ContentStatus.AGGREGATED);
            c.setAggregatedAt(clock.instant());
            aggregated++;
        }
        return new ChunkResult(aggregated, gone, 0, 0);
    }

    /** 청크 전체의 attempts를 올리고 상한 도달분은 FAILED로 밀어냄. FAILED 전환 수를 반환. */
    private int bumpAttempts(List<Content> chunk) {
        int failed = 0;
        for (Content c : chunk) {
            c.setAggregateAttempts(c.getAggregateAttempts() + 1);
            if (c.getAggregateAttempts() >= settings.maxAttempts()) {
                c.setStatus(ContentStatus.FAILED);
                failed++;
            }
        }
        return failed;
    }

    private Map<String, Map<String, Object>> indexDetails(List<Map<String, Object>> items) {
        Map<String, Map<String, Object>> byCode = new HashMap<>();
        for (Map<String, Object> item : items) {
            String sc = item.get("shortCode") instanceof String s ? s
                    : ShortCodes.fromUrl(item.get("url") instanceof String u ? u : null).orElse(null);
            if (sc != null) byCode.put(sc, item);
        }
        return byCode;
    }

    private Map<String, List<Map<String, Object>>> groupComments(List<Map<String, Object>> items) {
        Map<String, List<Map<String, Object>>> byCode = new HashMap<>();
        for (Map<String, Object> item : items) {
            ShortCodes.fromUrl(item.get("postUrl") instanceof String u ? u : null)
                    .ifPresent(sc -> byCode.computeIfAbsent(sc, k -> new ArrayList<>()).add(item));
        }
        return byCode;
    }
}
