package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.*;
import com.celfit.crawler.content.domain.*;
import com.celfit.crawler.settings.domain.*;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawPostDetailRepository extends JpaRepository<RawPostDetail, Long> {
    Optional<RawPostDetail> findTopByContentIdOrderByCapturedAtDesc(Long contentId);
}
