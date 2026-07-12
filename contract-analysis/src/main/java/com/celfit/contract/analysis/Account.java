package com.celfit.contract.analysis;

/** 서빙 계정 1행 (미러: analytics.v_accounts → accounts). handle = 인스타 username. */
public record Account(String handle, String displayName, String profileImageUrl, Long followers) {
}
