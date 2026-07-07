package com.celfit.crawler.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlRunRepository extends JpaRepository<CrawlRun, Long> {
    List<CrawlRun> findTop50ByOrderByIdDesc();
}
