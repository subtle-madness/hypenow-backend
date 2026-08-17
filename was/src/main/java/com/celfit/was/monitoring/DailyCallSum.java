package com.celfit.was.monitoring;

import java.time.LocalDate;

/**
 * 날짜별 콜 합 1행 — 브랜드·캠페인 두 파이프라인의 전역 집계가 같은 모양이라 공용이다.
 * calledOn은 KST 달력일(쓰는 쪽인 monitoring이 KST로 적재 — brand_call_count DDL 참조).
 *
 * <p>유저별 조회({@link MonitoringReadRepository.UserCallDailyRow})와 형태는 같지만 의미가
 * 다르다 — 이쪽은 유저·브랜드 축을 이미 접은 전역 합이라 유저 귀속 정보가 없다.
 */
public record DailyCallSum(LocalDate calledOn, long calls) {
}
