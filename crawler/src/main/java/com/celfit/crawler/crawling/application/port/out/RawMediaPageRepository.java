package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.RawMediaPage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawMediaPageRepository extends JpaRepository<RawMediaPage, Long> {
}
