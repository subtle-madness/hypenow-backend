package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.RawMediaPage;
import com.celfit.crawler.crawling.domain.RawSource;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawMediaPageRepository extends JpaRepository<RawMediaPage, Long> {

    /** 뷰티 판정 재료: 계정의 최신 릴스 페이지 — 프로필에 캡션이 없는 소스의 폴백 근거. */
    Optional<RawMediaPage> findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(
            Long influencerId, RawSource source);
}
