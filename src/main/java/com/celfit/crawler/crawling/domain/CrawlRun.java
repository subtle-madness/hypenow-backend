package com.celfit.crawler.crawling.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "crawl_run")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CrawlRun {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobName job;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private TriggerType triggerType;

    @Column(name = "category_id")
    private Long categoryId;

    private String keyword;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "apify_run_id")
    private String apifyRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status = RunStatus.RUNNING;

    @Column(name = "item_count")
    private Integer itemCount;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public CrawlRun(JobName job, TriggerType triggerType, Long categoryId,
                    String keyword, String actorId, Instant startedAt) {
        this.job = job;
        this.triggerType = triggerType;
        this.categoryId = categoryId;
        this.keyword = keyword;
        this.actorId = actorId;
        this.startedAt = startedAt;
    }

    public void finishOk(String apifyRunId, int itemCount, Instant at) {
        this.apifyRunId = apifyRunId;
        this.status = RunStatus.SUCCEEDED;
        this.itemCount = itemCount;
        this.finishedAt = at;
    }

    public void finishFailed(String error, Instant at) {
        this.status = RunStatus.FAILED;
        this.errorMessage = error;
        this.finishedAt = at;
    }
}
