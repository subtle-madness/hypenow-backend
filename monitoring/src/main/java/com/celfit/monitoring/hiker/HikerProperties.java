package com.celfit.monitoring.hiker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hiker 전송 설정. maxRetries·retryBackoff는 일시 오류(5xx·IO) 전용 재시도다 —
 * 404(대상 부재)는 결정적이라 재시도하지 않는다(스펙 §2-3).
 * 값이 null이면 JdkHikerHttp가 기본값(2회·2초)을 쓴다.
 */
@ConfigurationProperties("monitoring.hiker")
public record HikerProperties(String apiKey, String baseUrl, Duration requestTimeout,
		Integer maxRetries, Duration retryBackoff) {}
