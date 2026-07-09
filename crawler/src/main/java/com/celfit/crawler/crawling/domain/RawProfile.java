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
@Table(name = "raw_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawProfile {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "crawl_run_id", nullable = false)
    private Long crawlRunId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(insertable = false, updatable = false)
    private String username;

    @Column(insertable = false, updatable = false)
    private Long followers;

    public RawProfile(Long accountId, Long crawlRunId, Map<String, Object> payload, Instant capturedAt) {
        this.accountId = accountId;
        this.crawlRunId = crawlRunId;
        this.payload = payload;
        this.capturedAt = capturedAt;
    }
}
