package com.celfit.crawler.crawling.domain;

public enum InfluencerStatus {
    DISCOVERED, QUALIFIED, EXCLUDED,
    /** 소프트 딜리트 — 프로필 404(계정 삭제·개명)로 판명. 데이터는 보존, 모든 선정에서 제외. */
    DELETED
}
