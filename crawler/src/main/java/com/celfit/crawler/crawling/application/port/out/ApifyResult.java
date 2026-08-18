package com.celfit.crawler.crawling.application.port.out;

import java.util.List;
import java.util.Map;

/**
 * 크롤 실행 결과. runId는 Apify 실행 ID(비Apify 소스는 null),
 * requestCount는 비Apify 소스의 과금 요청 수(HikerAPI 페이지 등, 해당 없으면 null).
 * notFound는 404로 판명된 대상 username — 재시도 무의미(계정 소멸), 호출자가 소프트 딜리트한다.
 * confirmedEmpty는 이번 실행에서 자체·Hiker 폴백 양쪽 모두 빈 응답(계정 없음)으로 확인된
 * username — 프로필 컴포지트(SELF_HIKER_FALLBACK)만 채우며, 호출자가 수명 정책(30일 경과 시
 * 404 동일 취급)을 판정하는 재료다.
 */
public record ApifyResult(String runId, Integer requestCount, List<Map<String, Object>> items,
                          List<String> notFound, List<String> confirmedEmpty) {

    public ApifyResult(String runId, Integer requestCount, List<Map<String, Object>> items,
                       List<String> notFound) {
        this(runId, requestCount, items, notFound, List.of());
    }

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
