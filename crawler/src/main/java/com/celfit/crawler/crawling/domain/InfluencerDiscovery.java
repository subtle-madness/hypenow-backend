package com.celfit.crawler.crawling.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 발굴 출처 이력(append-only). keyword는 텍스트 스냅샷 — search_keyword id 참조 금지. */
@Entity
@Table(name = "influencer_discovery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InfluencerDiscovery {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "influencer_id", nullable = false)
    private Long influencerId;

    @Column(nullable = false)
    private String keyword;

    @Column(name = "discovered_post_short_code")
    private String discoveredPostShortCode;

    @Column(name = "discovered_at", nullable = false)
    private Instant discoveredAt;

    public InfluencerDiscovery(Long influencerId, String keyword,
                               String discoveredPostShortCode, Instant discoveredAt) {
        this.influencerId = influencerId;
        this.keyword = keyword;
        this.discoveredPostShortCode = discoveredPostShortCode;
        this.discoveredAt = discoveredAt;
    }
}
