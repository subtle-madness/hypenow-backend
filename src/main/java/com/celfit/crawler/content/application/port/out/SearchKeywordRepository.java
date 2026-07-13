package com.celfit.crawler.content.application.port.out;

import com.celfit.crawler.crawling.domain.*;
import com.celfit.crawler.content.domain.*;
import com.celfit.crawler.settings.domain.*;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchKeywordRepository extends JpaRepository<SearchKeyword, Long> {
    List<SearchKeyword> findByEnabledTrue();
    Optional<SearchKeyword> findByKeyword(String keyword);
    List<SearchKeyword> findAllByOrderByKeywordAsc();
}
