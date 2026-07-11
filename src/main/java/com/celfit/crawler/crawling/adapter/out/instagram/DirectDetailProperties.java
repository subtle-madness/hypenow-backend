package com.celfit.crawler.crawling.adapter.out.instagram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.direct-detail")
public record DirectDetailProperties(String postDocId, String postFriendlyName, Duration pageDelay) {}
