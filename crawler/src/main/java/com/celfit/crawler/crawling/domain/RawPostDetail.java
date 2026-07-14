package com.celfit.crawler.crawling.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "raw_post_detail")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawPostDetail {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "crawl_run_id", nullable = false)
    private Long crawlRunId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "short_code", insertable = false, updatable = false)
    private String shortCode;

    @Column(insertable = false, updatable = false)
    private String caption;

    @Column(insertable = false, updatable = false)
    private Long likes;

    @Column(name = "comments_count", insertable = false, updatable = false)
    private Long commentsCount;

    @Column(name = "video_play_count", insertable = false, updatable = false)
    private Long videoPlayCount;

    public RawPostDetail(Long contentId, Long crawlRunId, Map<String, Object> payload, Instant capturedAt) {
        this.contentId = contentId;
        this.crawlRunId = crawlRunId;
        this.payload = payload;
        this.capturedAt = capturedAt;
    }
}
