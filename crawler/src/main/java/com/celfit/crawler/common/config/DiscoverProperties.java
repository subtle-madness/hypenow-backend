package com.celfit.crawler.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.discover")
public record DiscoverProperties(int resultsLimit) {}
