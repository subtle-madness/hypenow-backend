package com.celfit.contract.analysis;

/**
 * 서빙 계정 1행 (미러: analytics.v_accounts → accounts). handle = 인스타 username.
 * externalLink: 프로필 외부 링크(raw payload externalUrl) — 없으면 NULL.
 */
public record Account(String handle, String displayName, String profileImageUrl, Long followers,
		String externalLink) {
}
