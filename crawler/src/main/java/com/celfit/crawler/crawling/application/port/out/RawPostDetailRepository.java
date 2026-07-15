package com.celfit.crawler.crawling.application.port.out;

import java.util.Optional;

import com.celfit.crawler.crawling.domain.RawPostDetail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawPostDetailRepository extends JpaRepository<RawPostDetail, Long> {
    Optional<RawPostDetail> findTopByContentIdOrderByCapturedAtDesc(Long contentId);
}
