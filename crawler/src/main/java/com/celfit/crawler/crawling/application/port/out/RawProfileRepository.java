package com.celfit.crawler.crawling.application.port.out;

import java.util.Optional;

import com.celfit.crawler.crawling.domain.RawProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawProfileRepository extends JpaRepository<RawProfile, Long> {
    Optional<RawProfile> findTopByInfluencerIdOrderByCapturedAtDesc(Long influencerId);
}
