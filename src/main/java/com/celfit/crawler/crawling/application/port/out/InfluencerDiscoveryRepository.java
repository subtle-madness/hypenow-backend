package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.InfluencerDiscovery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InfluencerDiscoveryRepository extends JpaRepository<InfluencerDiscovery, Long> {
}
