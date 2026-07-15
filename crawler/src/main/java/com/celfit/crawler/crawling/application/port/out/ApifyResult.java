package com.celfit.crawler.crawling.application.port.out;

import java.util.List;
import java.util.Map;

/**
 * 크롤 실행 결과. runId는 Apify 실행 ID(비Apify 소스는 null),
 * requestCount는 비Apify 소스의 과금 요청 수(HikerAPI 페이지 등, 해당 없으면 null).
 */
public record ApifyResult(String runId, Integer requestCount, List<Map<String, Object>> items) {

    /** requestCount 없는 소스용 (Apify·자체크롤 등). */
    public ApifyResult(String runId, List<Map<String, Object>> items) {
        this(runId, null, items);
    }
}
