package com.celfit.crawler.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "raw_discovery_post")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawDiscoveryPost {

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

    public RawDiscoveryPost(Long contentId, Long crawlRunId, Map<String, Object> payload, Instant capturedAt) {
        this.contentId = contentId;
        this.crawlRunId = crawlRunId;
        this.payload = payload;
        this.capturedAt = capturedAt;
    }
}
