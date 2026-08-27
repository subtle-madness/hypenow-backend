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

    /** 뷰티 계정 여부 — NULL이면 미판정. SIMILAR 잡의 시드 자격 조건(beauty=true, company=false). */
    private Boolean beauty;

    /**
     * 뷰티 회사(브랜드·쇼핑몰·살롱 등 사업자) 여부 — beauty=true의 하위 구분.
     * 회사는 명단 리스트업만 하고 수집·유사발굴 대상에서 제외된다.
     */
    @Column(name = "beauty_company")
    private Boolean beautyCompany;

    /** 4분류 원본(v2) — boolean은 이 값의 파생. NULL이면 미판정(구 3분류 시대 판정분 포함). */
    @Enumerated(EnumType.STRING)
    @Column(name = "beauty_class")
    private BeautyClass beautyClass;

    @Column(name = "beauty_source")
    private String beautySource;

    /** 판정 근거 한 줄 — 명단 페이지 툴팁 표시용. */
    @Column(name = "beauty_reason")
    private String beautyReason;

    /** 뷰티 판정 시각 — rejudge가 오래된 판정부터 재시도하는 기준(실패 배치는 옛 시각 유지). */
    @Column(name = "beauty_judged_at")
    private Instant beautyJudgedAt;

    /**
     * 판정에 실제로 넣은 캡션 건수. 0이면 실측 근거 없이 판정된 것 — 게시물 캡션이 쌓이면
     * 재판정 대상이 된다. NULL은 이 기록 도입 이전 판정분.
     */
    @Column(name = "beauty_caption_count")
    private Short beautyCaptionCount;

    /**
     * LLM이 밝힌 판정 주근거 — CAPTION·BIO·CATEGORY_ONLY 중 하나. CATEGORY_ONLY는 인스타그램
     * 자기신고 category만 보고 판단한 저확신 판정(계정주가 자율 선택하는 미검증 필드).
     */
    @Column(name = "beauty_basis")
    private String beautyBasis;

    /** F&B 계정 여부 — NULL이면 미판정(백필 대상). 수집·시드 편입은 fnb.pipeline-enabled 토글이 게이트. */
    private Boolean fnb;

    /** F&B 회사(식품·음료 브랜드·쇼핑몰) 여부 — fnb=true의 하위 구분. 토글 on이어도 수집 제외. */
    @Column(name = "fnb_company")
    private Boolean fnbCompany;

    /** F&B 5분류 원본 — boolean은 이 값의 파생. NULL이면 F&B 축 미판정. */
    @Enumerated(EnumType.STRING)
    @Column(name = "fnb_class")
    private CategoryClass fnbClass;

    @Column(name = "fnb_source")
    private String fnbSource;

    /** F&B 판정 근거 한 줄 — 명단 페이지 툴팁 표시용. */
    @Column(name = "fnb_reason")
    private String fnbReason;

    /** F&B 축 판정 시각. */
    @Column(name = "fnb_judged_at")
    private Instant fnbJudgedAt;

    /** F&B 판정에 실제로 넣은 캡션 건수 — 추후 F&B rejudge 도입 시 재료. */
    @Column(name = "fnb_caption_count")
    private Short fnbCaptionCount;

    /** F&B 판정 주근거 — CAPTION·BIO·CATEGORY_ONLY. */
    @Column(name = "fnb_basis")
    private String fnbBasis;

    /** 홈/리빙 계정 여부 — NULL이면 미판정(백필 대상). 수집·시드 편입은 home-living.pipeline-enabled 토글이 게이트. */
    @Column(name = "home_living")
    private Boolean homeLiving;

    /** 홈/리빙 회사(가구·리빙 브랜드·쇼핑몰) 여부 — home_living=true의 하위 구분. 토글 on이어도 수집 제외. */
    @Column(name = "home_living_company")
    private Boolean homeLivingCompany;

    /** 홈/리빙 5분류 원본 — boolean은 이 값의 파생. NULL이면 홈/리빙 축 미판정. */
    @Enumerated(EnumType.STRING)
    @Column(name = "home_living_class")
    private CategoryClass homeLivingClass;

    @Column(name = "home_living_source")
    private String homeLivingSource;

    /** 홈/리빙 판정 근거 한 줄 — 명단 페이지 툴팁 표시용. */
    @Column(name = "home_living_reason")
    private String homeLivingReason;

    /** 홈/리빙 축 판정 시각. */
    @Column(name = "home_living_judged_at")
    private Instant homeLivingJudgedAt;

    /** 홈/리빙 판정에 실제로 넣은 캡션 건수 — 정착 규칙(캡션 0건 → 1회 업그레이드)의 재료. */
    @Column(name = "home_living_caption_count")
    private Short homeLivingCaptionCount;

    /** 홈/리빙 판정 주근거 — CAPTION·BIO·CATEGORY_ONLY. */
    @Column(name = "home_living_basis")
    private String homeLivingBasis;

    /** SIMILAR 잡이 이 시드의 유사 계정 수확을 마친(또는 수확 불가로 확정한) 시각. NULL이면 시드 후보. */
    @Column(name = "similar_processed_at")
    private Instant similarProcessedAt;

    /** 첫 6개월 백필 완료 시각. NULL이면 백필 대상. */
    @Column(name = "first_collected_at")
    private Instant firstCollectedAt;

    @Column(name = "last_collected_at")
    private Instant lastCollectedAt;

    /** REELS 잡이 릴스 1페이지를 수확한 시각. NULL이면 릴스 백필 대상. */
    @Column(name = "last_reels_at")
    private Instant lastReelsAt;

    public Influencer(String username) {
        this.username = username;
    }

    /** 판정 결과 일괄 적용 — 파생 boolean을 beauty_class와 항상 일치시킨다. judgedAt은 호출자 몫. */
    public void classify(BeautyClass cls, String source, String reason, String basis) {
        this.beautyClass = cls;
        this.beauty = cls.beauty();
        this.beautyCompany = cls.company();
        this.beautySource = source;
        this.beautyReason = reason;
        this.beautyBasis = basis;
    }

    /** F&B 축 판정 적용 — 파생 boolean을 fnb_class와 항상 일치시킨다. judgedAt은 호출자 몫. */
    public void classifyFnb(CategoryClass cls, String source, String reason, String basis) {
        this.fnbClass = cls;
        this.fnb = cls.inCategory();
        this.fnbCompany = cls.company();
        this.fnbSource = source;
        this.fnbReason = reason;
        this.fnbBasis = basis;
    }

    /** 홈/리빙 축 판정 적용 — 파생 boolean을 home_living_class와 항상 일치시킨다. judgedAt은 호출자 몫. */
    public void classifyHomeLiving(CategoryClass cls, String source, String reason, String basis) {
        this.homeLivingClass = cls;
        this.homeLiving = cls.inCategory();
        this.homeLivingCompany = cls.company();
        this.homeLivingSource = source;
        this.homeLivingReason = reason;
        this.homeLivingBasis = basis;
    }
}
