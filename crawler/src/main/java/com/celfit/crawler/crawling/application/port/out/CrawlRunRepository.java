package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.*;
import com.celfit.crawler.content.domain.*;
import com.celfit.crawler.settings.domain.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlRunRepository extends JpaRepository<CrawlRun, Long> {
    List<CrawlRun> findTop50ByOrderByIdDesc();
    boolean existsByCategoryId(Long categoryId);
}
