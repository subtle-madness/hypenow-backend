package com.celfit.crawler.crawling.adapter.out.instagram;

import com.celfit.crawler.settings.domain.ProxySource;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인스타 자체크롤용 프록시 URL 모음 — 소스별로 하나씩(형식 http://user:pass@host:port).
 * 어느 소스를 쓸지는 런타임 설정(proxy.source)이 고른다. 미설정(빈칸) 소스를 고르면 직접 연결로 폴백된다.
 */
@ConfigurationProperties("crawler.proxy")
public record ProxyProperties(String apifyUrl, String dataimpulseResidentialUrl,
                              String dataimpulseMobileUrl, Duration requestTimeout) {

    /** 선택된 소스의 프록시 URL. DIRECT이거나 URL 미설정이면 null(=직접 연결). */
    public String urlFor(ProxySource source) {
        String url = switch (source) {
            case DIRECT -> null;
            case APIFY -> apifyUrl;
            case DATAIMPULSE_RESIDENTIAL -> dataimpulseResidentialUrl;
            case DATAIMPULSE_MOBILE -> dataimpulseMobileUrl;
        };
        return (url == null || url.isBlank()) ? null : url;
    }
}
