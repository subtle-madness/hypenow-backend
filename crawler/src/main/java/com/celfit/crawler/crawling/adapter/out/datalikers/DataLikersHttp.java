package com.celfit.crawler.crawling.adapter.out.datalikers;

/** DataLikers HTTP 전송 격리 — 테스트에서 fake로 대체. path는 base-url 이후 부분(쿼리 포함). */
public interface DataLikersHttp {
    String get(String path);
}
