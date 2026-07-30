package com.celfit.monitoring.hiker;

/**
 * 프로필 스냅샷 원재료 — rawJson은 응답 원문(스냅샷 감사·추후 재파싱용).
 * fullName·profilePicUrl은 profile_meta 저장용(계약 §3, v1.1) — 취득 불가 시 null.
 */
public record ProfileInfo(String username, String userId, Long followers, Long following,
		Long mediaCount, String fullName, String profilePicUrl, String rawJson) {}
