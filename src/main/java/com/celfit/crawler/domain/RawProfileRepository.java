package com.celfit.crawler.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawProfileRepository extends JpaRepository<RawProfile, Long> {
    Optional<RawProfile> findTopByAccountIdOrderByCapturedAtDesc(Long accountId);
}
