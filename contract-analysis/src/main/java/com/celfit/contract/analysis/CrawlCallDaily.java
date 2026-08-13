package com.celfit.contract.analysis;

import java.time.LocalDate;

/**
 * 크롤러 파이프라인 유료 요청 일별 집계 1행 (미러: analytics.v_crawl_call_daily → crawl_call_daily).
 * was 어드민 전역 크롤링 비용 API의 크롤러 몫 재료(설계 2026-08-13).
 *
 * <p>job은 crawler JobName의 이름 그대로(DISCOVER·QUALIFY·COLLECT·SIMILAR·REELS 등) — 라벨 매핑은
 * 소비자(was) 표현 계층 몫이고, 매핑에 없는 잡도 코드명으로 노출해 비용이 조용히 삼켜지지 않게 한다.
 *
 * <p>calledOn은 <b>KST 달력일</b> — monitoring의 brand_call_count.called_on·target_call_count.called_on과
 * 같은 시간대다(was가 세 소스를 한 경계로 합산하는 전제).
 *
 * <p>calls는 실제로 구매한 요청 수(crawl_run.request_count 합)이지 수집 건수가 아니다.
 * Apify 실행(결과 건당 과금)과 무료 소스는 뷰 단계에서 제외돼 여기 오지 않는다.
 */
public record CrawlCallDaily(String job, LocalDate calledOn, long calls) {
}
