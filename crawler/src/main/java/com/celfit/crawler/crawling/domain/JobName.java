package com.celfit.crawler.crawling.domain;

public enum JobName {
    DISCOVER, QUALIFY, COLLECT, BEAUTY, SIMILAR, REELS,
    /** 구 파이프라인 실행 이력(crawl_run) 판독 전용 — 새 실행 경로 없음. */
    AGGREGATE
}
