package com.celfit.crawler.job;

import com.celfit.crawler.apify.Actors;
import com.celfit.crawler.apify.ActorInputs;
import com.celfit.crawler.apify.ApifyException;
import com.celfit.crawler.apify.ShortCodes;
import com.celfit.crawler.config.AggregateProperties;
import com.celfit.crawler.domain.*;
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
    private final AggregateProperties props;
    private final Clock clock;

    public AggregateJob(ContentRepository contents, RawPostDetailRepository rawDetails,
                        RawCommentRepository rawComments, CrawlExecutor executor,
                        AggregateProperties props, Clock clock) {
        this.contents = contents;
        this.rawDetails = rawDetails;
        this.rawComments = rawComments;
        this.executor = executor;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Summary run(TriggerType trigger) {
        Instant cutoff = clock.instant().minus(Duration.ofDays(props.delayDays()));
        List<Content> due = contents.findDue(ContentStatus.QUALIFIED, cutoff,
                PageRequest.of(0, props.batchLimit()));

        int aggregated = 0, gone = 0, retried = 0, failed = 0;
        for (List<Content> chunk : ActorInputs.chunk(due, props.chunkSize())) {
            List<String> urls = chunk.stream()
                    .map(c -> ShortCodes.postUrl(c.getShortCode()))
                    .toList();

            Map<String, Map<String, Object>> detailByCode;
            Map<String, List<Map<String, Object>>> commentsByCode;
            Long detailRunId;
            Long commentRunId;
            try {
                var dx = executor.execute(JobName.AGGREGATE, trigger, null, null,
                        Actors.POST_DETAIL, ActorInputs.postDetail(urls));
                detailRunId = dx.runId();
                detailByCode = indexDetails(dx.items());
                var cx = executor.execute(JobName.AGGREGATE, trigger, null, null,
                        Actors.COMMENT, ActorInputs.comments(urls, props.commentsPerPost()));
                commentRunId = cx.runId();
                commentsByCode = groupComments(cx.items());
            } catch (ApifyException e) {
                // 청크 전체 재시도 대상 — attempts 상한 도달분은 FAILED로 밀어냄
                for (Content c : chunk) {
                    c.setAggregateAttempts(c.getAggregateAttempts() + 1);
                    if (c.getAggregateAttempts() >= props.maxAttempts()) {
                        c.setStatus(ContentStatus.FAILED);
                        failed++;
                    } else {
                        retried++;
                    }
                }
                continue;
            }

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
                c.setStatus(ContentStatus.AGGREGATED);
                c.setAggregatedAt(clock.instant());
                aggregated++;
            }
        }
        return new Summary(aggregated, gone, retried, failed);
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
