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

    private String keyword;

    @Column(name = "target_username")
    private String targetUsername;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "apify_run_id")
    private String apifyRunId;

    /** 비Apify 소스의 과금 요청 수 (HikerAPI 페이지 등). Apify 실행은 null. */
    @Column(name = "request_count")
    private Integer requestCount;

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

    public CrawlRun(JobName job, TriggerType trigger, String keyword,
                    String targetUsername, String actorId, Instant startedAt) {
        this.job = job;
        this.triggerType = trigger;
        this.keyword = keyword;
        this.targetUsername = targetUsername;
        this.actorId = actorId;
        this.startedAt = startedAt;
    }

    public void finishOk(String apifyRunId, Integer requestCount, int itemCount, Instant at) {
        this.apifyRunId = apifyRunId;
        this.requestCount = requestCount;
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
