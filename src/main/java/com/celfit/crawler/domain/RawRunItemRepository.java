package com.celfit.crawler.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RawRunItemRepository extends JpaRepository<RawRunItem, Long> {

    long countByCrawlRunId(Long crawlRunId);
}
