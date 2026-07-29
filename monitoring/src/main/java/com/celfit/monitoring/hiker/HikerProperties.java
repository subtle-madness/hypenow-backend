package com.celfit.monitoring.hiker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("monitoring.hiker")
public record HikerProperties(String apiKey, String baseUrl, Duration requestTimeout) {}
