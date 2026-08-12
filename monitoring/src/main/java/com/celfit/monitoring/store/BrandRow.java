package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.BrandStatus;
import java.time.LocalDate;

/**
 * 브랜드 스윕·등록이 쓰는 조회 단면. lastSweptOn null = 백필(첫 전량 수집) 미완 —
 * "수집 준비 중" 판별 기준(08-06 결정, 별도 상태 컬럼 없음).
 * collectionMonths = 자산 레벨 수집 창(개월), 절대 줄지 않는다.
 */
public record BrandRow(long id, String username, String igUserId, BrandStatus status,
		LocalDate lastSweptOn, int collectionMonths) {}
