package com.celfit.crawler.content.application.port.out;

import com.celfit.crawler.crawling.domain.*;
import com.celfit.crawler.content.domain.*;
import com.celfit.crawler.settings.domain.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryKeywordRepository extends JpaRepository<CategoryKeyword, Long> {
    List<CategoryKeyword> findByCategoryIdAndEnabledTrue(Long categoryId);
    List<CategoryKeyword> findByCategoryId(Long categoryId);
    /** UI 트리용 — 토글(UPDATE) 후에도 등록 순서 고정 (ORDER BY 없으면 힙 순서라 수정된 행이 뒤로 밀림). */
    List<CategoryKeyword> findByCategoryIdOrderByIdAsc(Long categoryId);
    List<CategoryKeyword> findByCategoryIdAndMainGroup(Long categoryId, String mainGroup);
    List<CategoryKeyword> findByCategoryIdAndMainGroupAndSubcategory(
            Long categoryId, String mainGroup, String subcategory);
    boolean existsByCategoryIdAndKeyword(Long categoryId, String keyword);
    void deleteByCategoryId(Long categoryId);
    void deleteByCategoryIdAndMainGroup(Long categoryId, String mainGroup);
    void deleteByCategoryIdAndMainGroupAndSubcategory(Long categoryId, String mainGroup, String subcategory);
}
