package com.celfit.crawler.content.application.port.out;

import com.celfit.crawler.crawling.domain.*;
import com.celfit.crawler.content.domain.*;
import com.celfit.crawler.settings.domain.*;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionRuleRepository extends JpaRepository<CollectionRule, Long> {
    Optional<CollectionRule> findByCategoryId(Long categoryId);
    void deleteByCategoryId(Long categoryId);
}
