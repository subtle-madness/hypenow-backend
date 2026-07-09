package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.*;
import com.celfit.crawler.content.domain.*;
import com.celfit.crawler.settings.domain.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawCommentRepository extends JpaRepository<RawComment, Long> {
    List<RawComment> findTop100ByContentIdOrderByIdDesc(Long contentId);
}
