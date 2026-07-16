package com.celfit.crawler.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.resnapshot")
public record ResnapshotProperties(int batchLimit) {}
