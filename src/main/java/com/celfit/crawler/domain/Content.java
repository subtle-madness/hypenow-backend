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

    /** 발굴 키워드의 중분류. 계층: 카테고리 > 대분류(main_group) > 중분류 > 소분류(discovery_keyword). */
    @Column(nullable = false)
    private String subcategory;

    /** 발굴 키워드의 대분류. */
    @Column(name = "main_group", nullable = false)
    private String mainGroup;

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

    /** 광고·협찬 표기 여부 — aggregate 때 상세 캡션·파트너십 필드로 판별. */
    @Column(name = "ad_marked", nullable = false)
    private boolean adMarked;

    public Content(String shortCode, ContentType contentType, String ownerUsername,
                   Instant uploadedAt, Long categoryId, String discoveryKeyword, Instant firstSeenAt) {
        this(shortCode, contentType, ownerUsername, uploadedAt, categoryId,
                discoveryKeyword, discoveryKeyword, discoveryKeyword, firstSeenAt);
    }

    public Content(String shortCode, ContentType contentType, String ownerUsername,
                   Instant uploadedAt, Long categoryId, String discoveryKeyword,
                   String subcategory, String mainGroup, Instant firstSeenAt) {
        this.shortCode = shortCode;
        this.contentType = contentType;
        this.ownerUsername = ownerUsername;
        this.uploadedAt = uploadedAt;
        this.categoryId = categoryId;
        this.discoveryKeyword = discoveryKeyword;
        this.subcategory = subcategory;
        this.mainGroup = mainGroup;
        this.firstSeenAt = firstSeenAt;
    }
}
