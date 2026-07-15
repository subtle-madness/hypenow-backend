package com.celfit.crawler.content.application.port.out;

import java.util.List;
import java.util.Optional;

import com.celfit.crawler.content.domain.SearchKeyword;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchKeywordRepository extends JpaRepository<SearchKeyword, Long> {
    List<SearchKeyword> findByEnabledTrue();
    Optional<SearchKeyword> findByKeyword(String keyword);
    List<SearchKeyword> findAllByOrderByKeywordAsc();
}
