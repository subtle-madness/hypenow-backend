package com.celfit.crawler.crawling.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 게시물 열거 응답의 페이지 단위 원형. 게시물별 필드는 content로 추출된다. */
@Entity
@Table(name = "raw_media_page")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawMediaPage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "influencer_id", nullable = false)
    private Long influencerId;

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

    public RawMediaPage(Long influencerId, Long crawlRunId, RawSource source,
                        Map<String, Object> payload, Instant capturedAt) {
        this.influencerId = influencerId;
        this.crawlRunId = crawlRunId;
        this.source = source;
        this.payload = payload;
        this.capturedAt = capturedAt;
    }
}
