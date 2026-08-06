package com.celfit.monitoring.hiker;

/**
 * 프로필 스냅샷 원재료 — rawJson은 응답 원문(스냅샷 감사·추후 재파싱용).
 * fullName·profilePicUrl은 profile_meta 저장용(계약 §3, v1.1) — 취득 불가 시 null.
 * biography는 브랜드 태그 모니터링의 등록 시 1회 관측값(brand_account.biography, 스펙 §2) — 취득 불가 시 null.
 */
public record ProfileInfo(String username, String userId, Long followers, Long following,
		Long mediaCount, String fullName, String profilePicUrl, String biography, String rawJson) {}
