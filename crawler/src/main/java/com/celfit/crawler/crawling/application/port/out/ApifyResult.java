package com.celfit.crawler.crawling.application.port.out;

import java.util.List;
import java.util.Map;

/**
 * 크롤 실행 결과. runId는 Apify 실행 ID(비Apify 소스는 null),
 * requestCount는 비Apify 소스의 과금 요청 수(HikerAPI 페이지 등, 해당 없으면 null).
 * notFound는 404로 판명된 대상 username — 재시도 무의미(계정 소멸), 호출자가 소프트 딜리트한다.
 */
public record ApifyResult(String runId, Integer requestCount, List<Map<String, Object>> items,
                          List<String> notFound) {

    public ApifyResult(String runId, Integer requestCount, List<Map<String, Object>> items) {
        this(runId, requestCount, items, List.of());
    }

    /** requestCount 없는 소스용 (Apify·자체크롤 등). */
    public ApifyResult(String runId, List<Map<String, Object>> items) {
        this(runId, null, items, List.of());
    }

    /** 자체크롤 소스가 계정 단위 404를 보고할 때. */
    public ApifyResult(String runId, List<Map<String, Object>> items, List<String> notFound) {
        this(runId, null, items, notFound);
    }
}
