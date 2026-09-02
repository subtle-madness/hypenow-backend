package com.celfit.contract.analysis;

/**
 * 서빙 계정 1행 (미러: analytics.v_accounts → accounts). handle = 인스타 username.
 * externalLink: 프로필 외부 링크(raw payload externalUrl) — 없으면 NULL.
 * beauty·fnb: 인플루언서 축(2026-08-31 F&B 서빙 개방) — was 발굴이 무필터=뷰티를 명시하는
 * 재료. raw influencer의 (beauty ∧ ¬beauty_company)·(fnb ∧ ¬fnb_company) 미러.
 */
public record Account(String handle, String displayName, String profileImageUrl, Long followers,
		String externalLink, Boolean beauty, Boolean fnb) {
}
