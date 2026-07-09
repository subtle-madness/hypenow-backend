package com.celfit.crawler.crawling.adapter.out.apify;

/** Apify HTTP 전송 격리 — 테스트에서 fake로 대체. url은 토큰 없이 완성형, 인증은 구현체가 헤더로. */
public interface ApifyHttp {
    String get(String url);
    String post(String url, String jsonBody);
}
