package com.celfit.crawler.crawling.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "influencer")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Influencer {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InfluencerStatus status = InfluencerStatus.DISCOVERED;

    /** 최신 팔로워 수 — qualify 판정 근거. raw_profile 원형에서 추출해 복사. */
    private Long followers;

    @Column(name = "last_profiled_at")
    private Instant lastProfiledAt;

    /** 첫 6개월 백필 완료 시각. NULL이면 백필 대상. */
    @Column(name = "first_collected_at")
    private Instant firstCollectedAt;

    @Column(name = "last_collected_at")
    private Instant lastCollectedAt;

    public Influencer(String username) {
        this.username = username;
    }
}
