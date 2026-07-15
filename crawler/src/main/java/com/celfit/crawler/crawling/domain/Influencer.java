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

    /**
     * 인스타그램 내부 user id(pk) — collect 열거 API 파라미터. raw_profile 원형에서 추출해 복사.
     * 방문 시 프로필 갱신이 실패(프록시 간헐 401 등)해도 이 값으로 열거를 계속한다.
     */
    @Column(name = "ig_user_id")
    private String igUserId;

    @Column(name = "last_profiled_at")
    private Instant lastProfiledAt;

    /** 뷰티 판정 주체 값 — CLAUDE(BEAUTY 잡)·MANUAL(명단 수동). MANUAL은 재판정에서도 보존. */
    public static final String BEAUTY_SOURCE_CLAUDE = "CLAUDE";
    public static final String BEAUTY_SOURCE_MANUAL = "MANUAL";

    /** 뷰티 계정 여부 — NULL이면 미판정. SIMILAR 잡의 시드 자격 조건(beauty=true). */
    private Boolean beauty;

    @Column(name = "beauty_source")
    private String beautySource;

    /** 판정 근거 한 줄 — 명단 페이지 툴팁 표시용. */
    @Column(name = "beauty_reason")
    private String beautyReason;

    /** SIMILAR 잡이 이 시드의 유사 계정 수확을 마친(또는 수확 불가로 확정한) 시각. NULL이면 시드 후보. */
    @Column(name = "similar_processed_at")
    private Instant similarProcessedAt;

    /** 첫 6개월 백필 완료 시각. NULL이면 백필 대상. */
    @Column(name = "first_collected_at")
    private Instant firstCollectedAt;

    @Column(name = "last_collected_at")
    private Instant lastCollectedAt;

    public Influencer(String username) {
        this.username = username;
    }
}
