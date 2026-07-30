package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.RawMediaPage;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawMediaPageRepository extends JpaRepository<RawMediaPage, Long> {

    /** 캡션 백필: id 커서로 결정적으로 소진한다(워터마크 재개 가능). */
    List<RawMediaPage> findByIdGreaterThanOrderById(Long id, Pageable pageable);
}
