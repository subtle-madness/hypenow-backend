package com.celfit.crawler.domain;

public enum ContentTypeFilter {
    ALL, REELS, FEED;

    public boolean allows(ContentType type) {
        return this == ALL || name().equals(type.name());
    }
}
