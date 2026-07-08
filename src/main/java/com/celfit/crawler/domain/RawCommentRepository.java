package com.celfit.crawler.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawCommentRepository extends JpaRepository<RawComment, Long> {
    List<RawComment> findTop100ByContentIdOrderByIdDesc(Long contentId);
}
