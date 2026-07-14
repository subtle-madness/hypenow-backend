package com.celfit.crawler.content.application.port.out;

import java.util.List;
import java.util.Optional;

import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentRepository extends JpaRepository<Content, Long> {

    Optional<Content> findByShortCode(String shortCode);

    List<Content> findByStatus(ContentStatus status);

    Page<Content> findByStatus(ContentStatus status, Pageable pageable);

    Page<Content> findByStatusIn(java.util.Collection<ContentStatus> statuses, Pageable pageable);

    List<Content> findByInfluencerIdAndStatus(Long influencerId, ContentStatus status);

    long countByStatus(ContentStatus status);
}
