package com.celfit.crawler.settings.domain;

/** 발굴(해시태그) 수집 소스. */
public enum DiscoverSource {
    /** Apify instagram-hashtag-scraper 액터. */
    ACTOR,
    /** HikerAPI /v2/hashtag/medias/top (해시태그 인기, 기본). */
    HIKER
}
