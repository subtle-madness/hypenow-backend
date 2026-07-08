package com.celfit.crawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.aggregate")
public record AggregateProperties(int delayDays, int batchLimit, int chunkSize,
                                  int commentsPerPost, int maxAttempts) {}
