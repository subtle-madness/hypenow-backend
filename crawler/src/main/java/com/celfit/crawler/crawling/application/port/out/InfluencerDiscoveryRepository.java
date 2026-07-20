package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.InfluencerDiscovery;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InfluencerDiscoveryRepository extends JpaRepository<InfluencerDiscovery, Long> {

    /** 인플루언서별 최초 발굴 맥락 — 이력이 append-only이므로 min(id) 행이 최초 발굴이다. */
    interface FirstDiscovery {
        Long getInfluencerId();
        String getKeyword();
        Instant getDiscoveredAt();
    }

    /** 명단 화면용: 주어진 인플루언서들의 최초 발굴(키워드·발굴일)만 — 현재 페이지 분량으로 호출한다. */
    @Query("select d.influencerId as influencerId, d.keyword as keyword, d.discoveredAt as discoveredAt "
            + "from InfluencerDiscovery d where d.id in "
            + "(select min(d2.id) from InfluencerDiscovery d2 "
            + " where d2.influencerId in :influencerIds group by d2.influencerId)")
    List<FirstDiscovery> findFirstDiscoveries(@Param("influencerIds") Collection<Long> influencerIds);
}
