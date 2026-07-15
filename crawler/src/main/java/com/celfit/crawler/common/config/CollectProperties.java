package com.celfit.crawler.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crawler.collect")
public record CollectProperties(int batchLimit, int commentsPerPost, int maxAttempts,
                                int revisitIntervalDays, boolean commentsEnabled) {}
