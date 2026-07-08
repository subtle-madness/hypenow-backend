package com.celfit.crawler.apify;

/** 사용하는 Apify 액터 id. */
public final class Actors {
    public static final String DISCOVERY = "apify~instagram-hashtag-scraper";
    /** 유형별 전용 상세 액터 — 범용 instagram-scraper 대비 taggedUsers·videoUrl 등을 보존한다. */
    public static final String DETAIL_REELS = "apify~instagram-reel-scraper";
    public static final String DETAIL_FEED = "apify~instagram-post-scraper";
    public static final String COMMENT = "apify~instagram-comment-scraper";
    public static final String PROFILE = "apify~instagram-profile-scraper";

    private Actors() {}
}
