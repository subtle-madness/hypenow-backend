package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.BrandStatus;
import java.time.LocalDate;

/**
 * 브랜드 스윕·등록이 쓰는 조회 단면. lastSweptOn null = 백필(첫 전량 수집) 미완 —
 * "수집 준비 중" 판별 기준(08-06 결정, 별도 상태 컬럼 없음).
 */
public record BrandRow(long id, String username, String igUserId, BrandStatus status,
		LocalDate lastSweptOn) {}
