package com.celfit.crawler.crawling.adapter.out.datalikers;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DataLikers(api.datalikers.com) 설정. HikerAPI와 별개 서비스·별개 키·별개 과금이라 독립 프로퍼티다.
 * costPerRequestUsd는 요금제(Day $0.0006 등)에 맞춰 yml에서 조정한다.
 */
@ConfigurationProperties("crawler.datalikers")
public record DataLikersProperties(String apiKey, String baseUrl, Duration requestTimeout,
                                   double costPerRequestUsd) {}
