package com.celfit.crawler.apify;

/** Apify HTTP 전송 격리 — 테스트에서 fake로 대체. url은 토큰 포함 완성형. */
public interface ApifyHttp {
    String get(String url);
    String post(String url, String jsonBody);
}
