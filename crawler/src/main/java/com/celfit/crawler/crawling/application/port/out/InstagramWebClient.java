package com.celfit.crawler.crawling.application.port.out;

import java.util.Map;

public interface InstagramWebClient {
    /** 쿠키를 관리하며 GET. 응답 본문(문자열) 반환. */
    Response get(String url);

    /** graphql POST. form-encoded body, 헤더 맵. 응답 본문 반환. */
    Response post(String url, String formBody, Map<String, String> headers);

    record Response(int status, String body, Map<String, String> setCookies) {}
}
