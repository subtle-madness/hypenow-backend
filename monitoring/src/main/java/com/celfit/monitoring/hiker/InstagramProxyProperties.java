package com.celfit.monitoring.hiker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 자체크롤 프록시·토글 설정. selfEnabled=false면 수집은 전량 Hiker(마일스톤 B 행동 변화 0);
 * geoKr·개통은 마일스톤 C에서. commentDocId/friendlyName은 자체 댓글 graphql용(env 주입).
 */
@ConfigurationProperties("monitoring.proxy")
public record InstagramProxyProperties(String residentialUrl, String mobileUrl, Duration requestTimeout,
		boolean geoKr, boolean selfEnabled, String commentDocId, String commentFriendlyName) {
}
