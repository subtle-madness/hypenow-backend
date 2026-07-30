package com.celfit.crawler.crawling.application.port.out;

import java.util.List;
import java.util.Optional;

import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawProfileRepository extends JpaRepository<RawProfile, Long> {
    Optional<RawProfile> findTopByInfluencerIdOrderByCapturedAtDesc(Long influencerId);

    /** 캡션 백필: 내장 타임라인을 담는 SELF_GQL만, id 커서로 결정적으로 소진한다. */
    List<RawProfile> findBySourceAndIdGreaterThanOrderById(RawSource source, Long id, Pageable pageable);
}
