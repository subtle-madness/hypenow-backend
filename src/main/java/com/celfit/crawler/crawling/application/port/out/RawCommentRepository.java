package com.celfit.crawler.crawling.application.port.out;

import java.util.List;

import com.celfit.crawler.crawling.domain.RawComment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawCommentRepository extends JpaRepository<RawComment, Long> {
    List<RawComment> findTop100ByContentIdOrderByIdDesc(Long contentId);
}
