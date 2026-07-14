package com.celfit.was.candidate;

/**
 * 후보 상태 — 라이프사이클 순서는 검토중 → 컨택 예정 → 협업 중.
 * 순서는 시맨틱(표시·정렬)일 뿐 전이를 강제하지 않는다 — 전이 규칙은 CandidateService 소관.
 * was 로컬 enum: 생산자+소비자 쌍이 없어 계약 모듈 대상이 아니다 (ARCHITECTURE §4-4).
 */
public enum CandidateStatus {
	REVIEWING,       // 검토중
	CONTACT_PLANNED, // 컨택 예정
	COLLABORATING    // 협업 중
}
