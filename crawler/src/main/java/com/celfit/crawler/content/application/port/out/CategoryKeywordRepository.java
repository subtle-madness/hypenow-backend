package com.celfit.crawler.content.application.port.out;

import com.celfit.crawler.crawling.domain.*;
import com.celfit.crawler.content.domain.*;
import com.celfit.crawler.settings.domain.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryKeywordRepository extends JpaRepository<CategoryKeyword, Long> {
    List<CategoryKeyword> findByCategoryIdAndEnabledTrue(Long categoryId);
    List<CategoryKeyword> findByCategoryId(Long categoryId);
    boolean existsByCategoryIdAndKeyword(Long categoryId, String keyword);
    void deleteByCategoryId(Long categoryId);
    void deleteByCategoryIdAndMainGroup(Long categoryId, String mainGroup);
    void deleteByCategoryIdAndMainGroupAndSubcategory(Long categoryId, String mainGroup, String subcategory);
}
