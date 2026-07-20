package com.celfit.crawler.crawling.domain;

public enum JobName {
    DISCOVER, QUALIFY,
    /** 응답이 raw_profile·raw_media_page에 1:1 무가공 저장 — raw_run_item 사본 생략. */
    COLLECT(false),
    BEAUTY, SIMILAR,
    /** 응답이 raw_media_page에 1:1 무가공 저장 — raw_run_item 사본 생략. */
    REELS(false),
    /** 실행 이력(crawl_run) 판독 전용 — 기능 제거·구 파이프라인으로 새 실행 경로 없음. */
    RESNAPSHOT, AGGREGATE;

    /** true면 성공 응답 아이템을 raw_run_item으로 아카이브. false는 타입 raw 테이블이 원형을 담는 잡. */
    private final boolean archivesRunItems;

    JobName() {
        this(true);
    }

    JobName(boolean archivesRunItems) {
        this.archivesRunItems = archivesRunItems;
    }

    public boolean archivesRunItems() {
        return archivesRunItems;
    }
}
