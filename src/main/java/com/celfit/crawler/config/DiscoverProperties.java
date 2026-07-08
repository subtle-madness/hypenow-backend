package com.celfit.crawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.discover")
public record DiscoverProperties(int resultsLimit) {}
