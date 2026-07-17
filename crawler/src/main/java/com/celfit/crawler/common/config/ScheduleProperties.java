package com.celfit.crawler.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.schedule")
public record ScheduleProperties(boolean enabled, String discoverCron,
                                 String qualifyCron, String collectCron,
                                 String beautyCron, String reelsCron) {}
