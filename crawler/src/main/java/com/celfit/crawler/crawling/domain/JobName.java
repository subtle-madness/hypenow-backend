package com.celfit.crawler.crawling.domain;

public enum JobName {
    DISCOVER, QUALIFY, COLLECT, BEAUTY, SIMILAR, REELS,
    /** 실행 이력(crawl_run) 판독 전용 — 기능 제거·구 파이프라인으로 새 실행 경로 없음. */
    RESNAPSHOT, AGGREGATE
}
