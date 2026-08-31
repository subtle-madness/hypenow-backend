package com.celfit.monitoring.hiker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 자체크롤 프록시 설정. 자체크롤 on/off 토글은 boot 프로퍼티가 아니라 런타임 app_setting
 * (ig-source.self-enabled / ig-source.force-hiker — IgSourceSettings)로 제어한다.
 * commentDocId/friendlyName은 자체 댓글 graphql용(env 주입).
 */
@ConfigurationProperties("monitoring.proxy")
public record InstagramProxyProperties(String residentialUrl, String mobileUrl, Duration requestTimeout,
		boolean geoKr, String commentDocId, String commentFriendlyName) {
}
