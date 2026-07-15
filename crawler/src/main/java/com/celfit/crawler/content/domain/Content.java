package com.celfit.crawler.content.domain;

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

    @Column(name = "influencer_id", nullable = false)
    private Long influencerId;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentStatus status = ContentStatus.PENDING;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "collected_at")
    private Instant collectedAt;

    @Column(name = "collect_attempts", nullable = false)
    private int collectAttempts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentOrigin origin;

    public Content(String shortCode, ContentType contentType, String ownerUsername,
                   Long influencerId, Instant uploadedAt, Instant firstSeenAt, ContentOrigin origin) {
        this.shortCode = shortCode;
        this.contentType = contentType;
        this.ownerUsername = ownerUsername;
        this.influencerId = influencerId;
        this.uploadedAt = uploadedAt;
        this.firstSeenAt = firstSeenAt;
        this.origin = origin;
    }
}
