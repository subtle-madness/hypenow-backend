package com.celfit.crawler.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "content")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Content {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, unique = true)
    private String shortCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false)
    private ContentType contentType;

    @Column(name = "owner_username", nullable = false)
    private String ownerUsername;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "discovery_keyword", nullable = false)
    private String discoveryKeyword;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentStatus status = ContentStatus.PENDING;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "qualified_at")
    private Instant qualifiedAt;

    @Column(name = "aggregated_at")
    private Instant aggregatedAt;

    @Column(name = "aggregate_attempts", nullable = false)
    private int aggregateAttempts;

    public Content(String shortCode, ContentType contentType, String ownerUsername,
                   Instant uploadedAt, Long categoryId, String discoveryKeyword, Instant firstSeenAt) {
        this.shortCode = shortCode;
        this.contentType = contentType;
        this.ownerUsername = ownerUsername;
        this.uploadedAt = uploadedAt;
        this.categoryId = categoryId;
        this.discoveryKeyword = discoveryKeyword;
        this.firstSeenAt = firstSeenAt;
    }
}
