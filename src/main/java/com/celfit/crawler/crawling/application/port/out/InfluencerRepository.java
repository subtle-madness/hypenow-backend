package com.celfit.crawler.crawling.application.port.out;

import java.util.List;
import java.util.Optional;

import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InfluencerRepository extends JpaRepository<Influencer, Long> {

    Optional<Influencer> findByUsername(String username);

    List<Influencer> findByStatus(InfluencerStatus status, Pageable pageable);

    long countByStatus(InfluencerStatus status);

    /** 비용 추정용: 아직 프로필 조회 전(discover 직후)인 인플루언서 수. */
    long countByStatusAndLastProfiledAtIsNull(InfluencerStatus status);

    /** 백필 대기: 판정 통과했지만 첫 수집(backfill)이 아직 안 된 인플루언서 수. */
    @Query("select count(i) from Influencer i where i.status = 'QUALIFIED' and i.firstCollectedAt is null")
    long countBackfillPending();

    /** 수집 대상: 판정 통과 + (백필 안 된 것 우선) 마지막 수집이 오래된 순. */
    @Query("select i from Influencer i where i.status = 'QUALIFIED' "
            + "order by case when i.firstCollectedAt is null then 0 else 1 end, i.lastCollectedAt asc nulls first")
    List<Influencer> findCollectTargets(Pageable pageable);
}
