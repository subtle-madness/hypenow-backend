package com.celfit.crawler.crawling.domain;

public enum JobName {
    DISCOVER, QUALIFY, COLLECT, BEAUTY, SIMILAR, REELS,
    /** 캡션 없는 재료로 비뷰티 판정된 계정의 프로필 재수집(로컬 GQL) — beauty rejudge의 전 단계. */
    RESNAPSHOT,
    /** 구 파이프라인 실행 이력(crawl_run) 판독 전용 — 새 실행 경로 없음. */
    AGGREGATE
}
