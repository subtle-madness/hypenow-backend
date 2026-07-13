package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.RawSource;
import java.util.Map;

/** 인플루언서 게시물 열거 — 페이지 1회 조회. 응답 원형(Map)을 그대로 반환한다. */
public interface UserMediaPageFetcher {
    RawSource source();
    /** cursor null이면 첫 페이지. 반환 payload는 응답 JSON 원형. */
    Map<String, Object> fetchPage(String userId, String cursor);
}
