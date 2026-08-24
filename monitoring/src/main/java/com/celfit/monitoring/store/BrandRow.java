package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.BrandStatus;
import java.time.LocalDate;

/**
 * 브랜드 스윕·등록이 쓰는 조회 단면. lastSweptOn null = 백필(첫 전량 수집) 미완 —
 * "수집 준비 중" 판별 기준(08-06 결정, 별도 상태 컬럼 없음).
 * collectionMonths = 자산 레벨 수집 창(개월), 절대 줄지 않는다.
 *
 * <p>hasOwnLink(2026-08-19 경쟁사 판정 제거 설계) — "이 브랜드에 활성 own 연결이 하나 이상 있다"는
 * was 원장(app.brand_monitorings) 파생 플래그. false면 광고 표기 판정을 스킵한다(스윕 경로는
 * {@link com.celfit.monitoring.service.BrandCollectService}, 백필 경로는
 * {@link com.celfit.monitoring.ad.AdDisclosureJudgeService}). 기본값 true(과판정 방향 — 동기화
 * 실패의 안전 쪽).
 */
public record BrandRow(long id, String username, String igUserId, BrandStatus status,
		LocalDate lastSweptOn, int collectionMonths, boolean hasOwnLink) {}
