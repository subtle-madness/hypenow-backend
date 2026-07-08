package com.celfit.crawler.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryKeywordRepository extends JpaRepository<CategoryKeyword, Long> {
    List<CategoryKeyword> findByCategoryIdAndEnabledTrue(Long categoryId);
    List<CategoryKeyword> findByCategoryId(Long categoryId);
    boolean existsByCategoryIdAndKeyword(Long categoryId, String keyword);
    void deleteByCategoryId(Long categoryId);
}
