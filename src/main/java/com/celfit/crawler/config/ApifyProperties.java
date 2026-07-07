package com.celfit.crawler.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.apify")
public record ApifyProperties(String token, String baseUrl, Duration pollInterval, Duration runTimeout) {}
