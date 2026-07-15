package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.RawRunItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawRunItemRepository extends JpaRepository<RawRunItem, Long> {

    long countByCrawlRunId(Long crawlRunId);
}
