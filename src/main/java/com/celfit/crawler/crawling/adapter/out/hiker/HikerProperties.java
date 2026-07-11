package com.celfit.crawler.crawling.adapter.out.hiker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.hiker")
public record HikerProperties(String apiKey, String baseUrl, Duration requestTimeout) {}
