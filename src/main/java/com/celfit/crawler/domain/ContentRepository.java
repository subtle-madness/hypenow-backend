package com.celfit.crawler.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentRepository extends JpaRepository<Content, Long> {

    Optional<Content> findByShortCode(String shortCode);

    List<Content> findByStatus(ContentStatus status);

    Page<Content> findByStatus(ContentStatus status, Pageable pageable);

    Page<Content> findByStatusIn(java.util.Collection<ContentStatus> statuses, Pageable pageable);

    /** aggregate 대상: 판정 통과 + 미집계 + 업로드가 컷오프(now-3일) 이전(경계 포함). */
    @Query("""
            select c from Content c
            where c.status = :status and c.aggregatedAt is null and c.uploadedAt <= :cutoff
            order by c.uploadedAt asc""")
    List<Content> findDue(@Param("status") ContentStatus status,
                          @Param("cutoff") Instant cutoff,
                          Pageable pageable);

    long countByStatus(ContentStatus status);

    boolean existsByCategoryId(Long categoryId);

    long countByStatusAndAggregatedAtIsNullAndUploadedAtLessThanEqual(ContentStatus status, Instant cutoff);
}
