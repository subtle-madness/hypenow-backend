package com.celfit.crawler.crawling.adapter.out.apify;

import java.time.Duration;

public interface Sleeper {
    void sleep(Duration duration);
}
