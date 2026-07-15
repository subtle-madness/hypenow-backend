package com.celfit.crawler.crawling.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "raw_comment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawComment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "crawl_run_id", nullable = false)
    private Long crawlRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RawSource source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Setter
    private String writer;

    @Setter
    private String text;

    @Setter
    @Column(name = "written_at")
    private String writtenAt;

    public RawComment(Long contentId, Long crawlRunId, RawSource source,
                      Map<String, Object> payload, Instant capturedAt) {
        this.contentId = contentId;
        this.crawlRunId = crawlRunId;
        this.source = source;
        this.payload = payload;
        this.capturedAt = capturedAt;
    }
}
