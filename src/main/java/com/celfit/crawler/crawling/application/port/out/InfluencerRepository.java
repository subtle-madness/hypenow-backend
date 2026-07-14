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

    long countByStatus(InfluencerStatus status);

    /** 비용 추정용: 아직 프로필 조회 전(discover 직후)인 인플루언서 수. */
    long countByStatusAndLastProfiledAtIsNull(InfluencerStatus status);

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
}
