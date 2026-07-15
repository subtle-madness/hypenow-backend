package com.celfit.crawler.crawling.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    /** 백필 대기: 판정 통과했지만 첫 수집(backfill)이 아직 안 된 인플루언서 수. */
    @Query("select count(i) from Influencer i where i.status = 'QUALIFIED' and i.firstCollectedAt is null")
    long countBackfillPending();

    /** 추적 대기: 첫 수집은 끝났지만 재방문 주기(revisitBefore)가 지나 다시 수집 대상이 될 인플루언서 수. */
    @Query("select count(i) from Influencer i where i.status = 'QUALIFIED' "
            + "and i.firstCollectedAt is not null and i.lastCollectedAt < :revisitBefore")
    long countTrackDue(@Param("revisitBefore") Instant revisitBefore);

    /**
     * 수집 대상: 판정 통과 + (백필 안 된 것 우선) + 재방문 주기(revisitBefore)가 지난 것만.
     * 최근에 이미 수집한(주기 안 지난) 인플루언서는 대상에서 빠진다.
     */
    @Query("select i from Influencer i where i.status = 'QUALIFIED' "
            + "and (i.firstCollectedAt is null or i.lastCollectedAt < :revisitBefore) "
            + "order by case when i.firstCollectedAt is null then 0 else 1 end, i.lastCollectedAt asc nulls first")
    List<Influencer> findCollectTargets(@Param("revisitBefore") Instant revisitBefore, Pageable pageable);

    /** BEAUTY 잡 대상: 판정 통과했지만 뷰티 미판정 — id 순 Pageable로 결정적으로 소진한다. */
    List<Influencer> findByStatusAndBeautyIsNull(InfluencerStatus status, Pageable pageable);

    /** BEAUTY 재판정(rejudge) 대상: CLAUDE 판정분만 — MANUAL은 선정 자체에서 제외된다. */
    List<Influencer> findByStatusAndBeautySource(InfluencerStatus status, String beautySource,
                                                 Pageable pageable);

    /** SIMILAR 시드: 뷰티 확정 + 미수확 — id 순 Pageable로 결정적으로 소진한다. */
    List<Influencer> findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
            InfluencerStatus status, Pageable pageable);

    /** 비용 추정용. */
    long countByStatusAndBeautyIsNull(InfluencerStatus status);

    long countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(InfluencerStatus status);

    /** 비용 추정용: pk 미보유라 SIMILAR가 username 해석 1회를 추가로 사는 시드 수. */
    long countByStatusAndBeautyTrueAndSimilarProcessedAtIsNullAndIgUserIdIsNull(InfluencerStatus status);
}
