package com.celfit.crawler.crawling.application.port.out;

import java.util.List;

import com.celfit.crawler.crawling.domain.CrawlRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlRunRepository extends JpaRepository<CrawlRun, Long> {
    List<CrawlRun> findTop50ByOrderByIdDesc();
}
