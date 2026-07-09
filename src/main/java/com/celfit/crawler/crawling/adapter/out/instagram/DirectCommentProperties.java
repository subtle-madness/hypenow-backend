package com.celfit.crawler.crawling.adapter.out.instagram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crawler.direct-comment")
public record DirectCommentProperties(String proxyUrl, Duration requestTimeout, Duration pageDelay,
                                      String commentDocId, String commentFriendlyName) {}
