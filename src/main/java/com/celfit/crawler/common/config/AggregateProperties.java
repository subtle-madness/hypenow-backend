package com.celfit.crawler.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.aggregate")
public record AggregateProperties(int delayDays, int batchLimit, int chunkSize,
                                  int commentsPerPost, int maxAttempts) {}
