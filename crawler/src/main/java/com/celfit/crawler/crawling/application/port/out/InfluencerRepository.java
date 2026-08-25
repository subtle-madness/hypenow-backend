package com.celfit.crawler.crawling.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InfluencerRepository extends JpaRepository<Influencer, Long> {

    Optional<Influencer> findByUsername(String username);

    List<Influencer> findByStatus(InfluencerStatus status, Pageable pageable);

    /** 인플루언서 명단 화면: 판정 완료(QUALIFIED/EXCLUDED) 등 상태 집합으로 페이징 조회. */
    org.springframework.data.domain.Page<Influencer> findByStatusIn(
            java.util.Collection<InfluencerStatus> statuses, Pageable pageable);

    long countByStatus(InfluencerStatus status);

    /** 판정 가능분: followers를 이미 확보한(레거시 이관·이전 프로필) 인플루언서 — API 호출 없이 즉시 판정. */
    List<Influencer> findByStatusAndFollowersIsNotNull(InfluencerStatus status);

    /** 프로필 수집 배치: followers 미확보 인플루언서 — id 순 Pageable로 결정적으로 소진한다. */
    List<Influencer> findByStatusAndFollowersIsNull(InfluencerStatus status, Pageable pageable);

    /** 비용 추정용: 프로필(followers) 미확보라 qualify가 API를 호출해야 하는 인플루언서 수. */
    long countByStatusAndFollowersIsNull(InfluencerStatus status);

    /** 백필 대기: 판정 통과 + 뷰티 확정이지만 첫 수집(backfill)이 아직 안 된 인플루언서 수. */
    @Query("select count(i) from Influencer i where i.status = 'QUALIFIED' and i.beauty = true "
            + "and (i.beautyCompany is null or i.beautyCompany = false) "
            + "and i.firstCollectedAt is null")
    long countBackfillPending();

    /** 추적 대기: 첫 수집은 끝났지만 재방문 주기(revisitBefore)가 지나 다시 수집 대상이 될 뷰티 인플루언서 수. */
    @Query("select count(i) from Influencer i where i.status = 'QUALIFIED' and i.beauty = true "
            + "and (i.beautyCompany is null or i.beautyCompany = false) "
            + "and i.firstCollectedAt is not null and i.lastCollectedAt < :revisitBefore")
    long countTrackDue(@Param("revisitBefore") Instant revisitBefore);

    /**
     * 수집 대상: 판정 통과 + 뷰티 확정(beauty=true) + (백필 안 된 것 우선) + 재방문 주기(revisitBefore)가
     * 지난 것만. 비뷰티·미판정은 QUALIFIED여도 방문하지 않고, 최근에 이미 수집한(주기 안 지난)
     * 인플루언서도 대상에서 빠진다.
     */
    @Query("select i from Influencer i where i.status = 'QUALIFIED' and i.beauty = true "
            + "and (i.beautyCompany is null or i.beautyCompany = false) "
            + "and (i.firstCollectedAt is null or i.lastCollectedAt < :revisitBefore) "
            + "order by case when i.firstCollectedAt is null then 0 else 1 end, i.lastCollectedAt asc nulls first")
    List<Influencer> findCollectTargets(@Param("revisitBefore") Instant revisitBefore, Pageable pageable);

    /** BEAUTY 잡 대상: 판정 통과했지만 뷰티 미판정 — id 순 Pageable로 결정적으로 소진한다. */
    List<Influencer> findByStatusAndBeautyIsNull(InfluencerStatus status, Pageable pageable);

    /**
     * F&B 백필 대상: 뷰티 축은 판정 완료(MANUAL 포함 — 백필은 뷰티 판정을 덮지 않으므로 안전)지만
     * F&B 축이 미판정인 계정 — 카테고리 확장(스펙 2026-08-23 §3)의 기존 판정분 전체 재판정 경로.
     * id 순 Pageable로 결정적으로 소진한다.
     */
    @Query("select i from Influencer i where i.status = :status and i.beauty is not null "
            + "and i.fnb is null order by i.id")
    List<Influencer> findFnbBackfillTargets(@Param("status") InfluencerStatus status, Pageable pageable);

    /**
     * BEAUTY 재판정(rejudge) 대상: CLAUDE가 비뷰티로 판정했지만 판정 후 프로필 재료가 갱신된
     * (새 raw_profile 스냅샷이 생긴) 계정만 — 재료가 그대로면 같은 판정만 반복하므로 배치 낭비다.
     * MANUAL은 선정 자체에서 제외되고, 뷰티 판정분은 재검하지 않는다(캡션이 뷰티→비뷰티로
     * 뒤집는 사례는 관측되지 않음 — 2026-07-16 실험). 오래된 판정 우선(시각 미기록 = 가장 오래됨).
     */
    @Query("select i from Influencer i where i.status = :status and i.beautySource = :beautySource "
            + "and i.beauty = false and (i.beautyJudgedAt is null or i.beautyJudgedAt < "
            + "(select max(rp.capturedAt) from RawProfile rp where rp.influencerId = i.id)) "
            + "order by i.beautyJudgedAt asc nulls first, i.id")
    List<Influencer> findRejudgeTargets(@Param("status") InfluencerStatus status,
                                        @Param("beautySource") String beautySource,
                                        Pageable pageable);

    /**
     * BEAUTY 캡션 재판정 대상: 캡션 0건으로 판정됐지만 그 뒤로 릴스 페이지(실측 캡션)가 쌓인 계정.
     * 기존 findRejudgeTargets와 달리 beauty 값을 조건에 걸지 않는다 — 뷰티로 잘못 통과한
     * false positive를 실측 캡션으로 되돌리는 것이 이 경로의 목적이다(2026-07-30 오판 886개).
     * beauty_caption_count가 NULL인 기록 이전 판정분은 대상이 아니다(V22 백필이 0으로 표시한 것만).
     * minItems는 "캡션이 실제로 있을 만한 페이지"의 하한 — 아이템 수가 캡션 수의 근사다.
     * 재판정하면 judged_at이 갱신돼 조건이 닫힌다 — 캡션을 못 뽑아 count가 0으로 남아도
     * captured_at은 고정이라 같은 페이지로 다시 선정되지 않는다(무한 재대상 방지).
     */
    @Query(value = "select i.* from influencer i "
            + "where i.status = :status and i.beauty_source = :beautySource "
            + "and i.beauty_caption_count = 0 "
            + "and exists (select 1 from raw_media_page rmp "
            + "  where rmp.influencer_id = i.id and rmp.source = 'HIKER_V2_CLIPS' "
            + "  and jsonb_typeof(jsonb_extract_path(rmp.payload, 'response', 'items')) = 'array' "
            + "  and jsonb_array_length(jsonb_extract_path(rmp.payload, 'response', 'items')) >= :minItems "
            + "  and (i.beauty_judged_at is null or rmp.captured_at > i.beauty_judged_at)) "
            + "order by i.beauty_judged_at asc nulls first, i.id", nativeQuery = true)
    List<Influencer> findCaptionRejudgeTargets(@Param("status") String status,
                                               @Param("beautySource") String beautySource,
                                               @Param("minItems") int minItems,
                                               Pageable pageable);

    /**
     * REELS 잡 대상: 뷰티 확정 + (릴스 백필 우선) + 재방문 주기(revisitBefore)가 지난 것만.
     * 백필끼리는 프로필 수집 완료 계정 우선 — 피드만 있고 릴스가 없는 "짝 안 맞는" 계정부터 채운다.
     */
    @Query("select i from Influencer i where i.status = 'QUALIFIED' and i.beauty = true "
            + "and (i.beautyCompany is null or i.beautyCompany = false) "
            + "and (i.lastReelsAt is null or i.lastReelsAt < :revisitBefore) "
            + "order by case when i.lastReelsAt is null then 0 else 1 end, "
            + "case when i.lastCollectedAt is null then 1 else 0 end, "
            + "i.lastReelsAt asc nulls first, i.id")
    List<Influencer> findReelsTargets(@Param("revisitBefore") Instant revisitBefore, Pageable pageable);

    /** 대시보드 게시물 수집 카드의 계정 기준: 프로필 스냅샷(수집 방문)을 1회 이상 수행한 계정 수. */
    long countByLastCollectedAtIsNotNull();

    /** 데일리 대시보드: 기준 시각(오늘 자정) 이후 프로필 스냅샷을 마친 계정 수. */
    long countByLastCollectedAtGreaterThanEqual(Instant since);

    /** 데일리 대시보드: 기준 시각 이후 릴스 수집을 마친 계정 수. */
    long countByLastReelsAtGreaterThanEqual(Instant since);

    /** 데일리 대시보드: 기준 시각 이후 피드·릴스 둘 다 마친(사이클 완주) 계정 수. */
    long countByLastCollectedAtGreaterThanEqualAndLastReelsAtGreaterThanEqual(Instant c, Instant r);

    /** 대시보드 게시물 수집 카드의 계정 기준: 릴스 수집을 1회 이상 수행한 계정 수. */
    long countByLastReelsAtIsNotNull();

    /** 대시보드 게시물 수집 카드의 계정 기준: 어느 쪽이든 수집을 수행한 계정 수. */
    @Query("select count(i) from Influencer i "
            + "where i.lastCollectedAt is not null or i.lastReelsAt is not null")
    long countAnyCollected();

    /** 대시보드·비용 추정용: 릴스 수집 대기(백필 + 주기 도래) 수. */
    @Query("select count(i) from Influencer i where i.status = 'QUALIFIED' and i.beauty = true "
            + "and (i.beautyCompany is null or i.beautyCompany = false) "
            + "and (i.lastReelsAt is null or i.lastReelsAt < :revisitBefore)")
    long countReelsDue(@Param("revisitBefore") Instant revisitBefore);

    /** SIMILAR 시드: 뷰티 인플루언서(회사 제외) + 미수확 — id 순 Pageable로 결정적으로 소진한다. */
    @Query("select i from Influencer i where i.status = :status and i.beauty = true "
            + "and (i.beautyCompany is null or i.beautyCompany = false) "
            + "and i.similarProcessedAt is null order by i.id")
    List<Influencer> findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
            @Param("status") InfluencerStatus status, Pageable pageable);

    /** 비용 추정용. */
    long countByStatusAndBeautyIsNull(InfluencerStatus status);

    /** 대시보드 뷰티 판정 그룹용: 뷰티(true)/비뷰티(false) 판정 수. */
    long countByStatusAndBeauty(InfluencerStatus status, Boolean beauty);

    /** 뷰티 판정 4분류 집계 — 대시보드 타일용(BEAUTY_SERVICE 등). */
    long countByStatusAndBeautyClass(InfluencerStatus status, BeautyClass beautyClass);

    /** 대시보드 뷰티 판정 그룹용: 뷰티 인플루언서(회사 제외) 수 — 수집·유사발굴 대상과 동일 기준. */
    @Query("select count(i) from Influencer i where i.status = :status and i.beauty = true "
            + "and (i.beautyCompany is null or i.beautyCompany = false)")
    long countBeautyInfluencers(@Param("status") InfluencerStatus status);

    /** 대시보드 뷰티 판정 그룹용: 뷰티 회사 수 — 리스트업 전용(수집·유사발굴 제외). */
    @Query("select count(i) from Influencer i where i.status = :status and i.beauty = true "
            + "and i.beautyCompany = true")
    long countBeautyCompanies(@Param("status") InfluencerStatus status);

    /** 명단의 뷰티 회사 리스트업 뷰 — 회사로 판정된 계정만. */
    org.springframework.data.domain.Page<Influencer> findByStatusInAndBeautyTrueAndBeautyCompanyTrue(
            java.util.Collection<InfluencerStatus> statuses, Pageable pageable);

    /** 명단 뷰티 필터: 선택한 4분류(beauty_class)만. */
    org.springframework.data.domain.Page<Influencer> findByStatusInAndBeautyClassIn(
            java.util.Collection<InfluencerStatus> statuses,
            java.util.Collection<BeautyClass> classes, Pageable pageable);

    /** 명단 뷰티 필터: 미판정(beauty_class 없음 — 구 3분류 시대 판정분 포함)만. */
    org.springframework.data.domain.Page<Influencer> findByStatusInAndBeautyClassIsNull(
            java.util.Collection<InfluencerStatus> statuses, Pageable pageable);

    /** 명단 뷰티 필터: 선택 분류 + 미판정을 함께 체크한 경우. */
    @Query("select i from Influencer i where i.status in :statuses "
            + "and (i.beautyClass in :classes or i.beautyClass is null)")
    org.springframework.data.domain.Page<Influencer> findByStatusInAndBeautyClassInOrNull(
            @Param("statuses") java.util.Collection<InfluencerStatus> statuses,
            @Param("classes") java.util.Collection<BeautyClass> classes, Pageable pageable);

    @Query("select count(i) from Influencer i where i.status = :status and i.beauty = true "
            + "and (i.beautyCompany is null or i.beautyCompany = false) "
            + "and i.similarProcessedAt is null")
    long countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(@Param("status") InfluencerStatus status);

    /** 비용 추정용: pk 미보유라 SIMILAR가 username 해석 1회를 추가로 사는 시드 수(회사 제외). */
    @Query("select count(i) from Influencer i where i.status = :status and i.beauty = true "
            + "and (i.beautyCompany is null or i.beautyCompany = false) "
            + "and i.similarProcessedAt is null and i.igUserId is null")
    long countByStatusAndBeautyTrueAndSimilarProcessedAtIsNullAndIgUserIdIsNull(
            @Param("status") InfluencerStatus status);
}
