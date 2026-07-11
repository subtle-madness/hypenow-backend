package com.celfit.crawler.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.qualify")
public record QualifyProperties(int batchLimit) {}
