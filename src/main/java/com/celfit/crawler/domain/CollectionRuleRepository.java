package com.celfit.crawler.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionRuleRepository extends JpaRepository<CollectionRule, Long> {
    Optional<CollectionRule> findByCategoryId(Long categoryId);
}
