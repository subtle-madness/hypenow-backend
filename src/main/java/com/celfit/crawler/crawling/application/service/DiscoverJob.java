package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 발굴 잡 — 인플루언서 중심 재구현은 Task 6에서 진행. 현재는 빈 셸. */
@Service
public class DiscoverJob {

    public record Summary(int newInfluencers, int duplicates, int skipped, int failedKeywords) {}

    @Transactional
    public Summary run(TriggerType trigger) {
        return new Summary(0, 0, 0, 0);
    }
}
