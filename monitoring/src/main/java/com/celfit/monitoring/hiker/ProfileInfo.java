package com.celfit.monitoring.hiker;

/** 프로필 스냅샷 원재료 — rawJson은 응답 원문(스냅샷 감사·추후 재파싱용). */
public record ProfileInfo(String username, String userId, Long followers, Long following,
		Long mediaCount, String rawJson) {}
