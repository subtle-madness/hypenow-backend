package com.celfit.crawler.crawling.application.port.out;

import java.util.Optional;

import com.celfit.crawler.crawling.domain.RawProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawProfileRepository extends JpaRepository<RawProfile, Long> {
    Optional<RawProfile> findTopByInfluencerIdOrderByCapturedAtDesc(Long influencerId);

    /** 데일리 대시보드: 기준 시각(오늘 자정) 이후 저장된 팔로워 스냅샷 수. */
    long countByCapturedAtGreaterThanEqual(java.time.Instant since);
}
