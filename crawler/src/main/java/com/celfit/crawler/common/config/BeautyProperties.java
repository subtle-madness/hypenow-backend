package com.celfit.crawler.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.beauty")
public record BeautyProperties(int batchLimit) {}
