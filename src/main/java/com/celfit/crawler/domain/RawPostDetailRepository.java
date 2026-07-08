package com.celfit.crawler.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawPostDetailRepository extends JpaRepository<RawPostDetail, Long> {
    Optional<RawPostDetail> findTopByContentIdOrderByCapturedAtDesc(Long contentId);
}
